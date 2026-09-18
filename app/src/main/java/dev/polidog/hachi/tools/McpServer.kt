package dev.polidog.hachi.tools

import android.util.Log
import dev.polidog.hachi.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The house, as Home Assistant's MCP server describes it.
 *
 * Nothing here knows what a light or a speaker is: whatever `tools/list` returns is handed to Gemini
 * as function declarations, and whatever Gemini calls is passed back to `tools/call`. The knowledge
 * about entities, areas and services stays on the Home Assistant side, where it already lives.
 *
 * JSON-RPC over Streamable HTTP, which is two kinds of POST: a request that carries an `id` and
 * comes back with a result, and a notification that carries none and comes back empty. The session
 * id the server hands out on `initialize` has to be echoed on everything after it.
 */
class McpServer(private val endpoint: String, private val token: String) {
    // Home Assistant hands out no session id at all and simply accepts what follows; other servers
    // require theirs to be echoed. So the handshake is what gates a request, not the id.
    @Volatile private var initialized = false
    @Volatile private var session: String? = null
    private var lastId = 0

    /** Blocking. The raw tool objects as the server describes them; empty if it could not be asked. */
    fun tools(): JSONArray =
        request("tools/list", JSONObject())?.optJSONArray("tools") ?: JSONArray()

    /** Blocking. The result in the shape a tool returns it, including on failure. */
    fun call(name: String, args: JSONObject): JSONObject {
        val result = request("tools/call", JSONObject().put("name", name).put("arguments", args))
            ?: return failure("house_unavailable")
        return mcpResult(result)
    }

    /** Blocking. Null when the call did not come back, for any reason. */
    private fun request(method: String, params: JSONObject): JSONObject? {
        if (!initialized && !handshake()) return null
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", ++lastId)
            .put("method", method)
            .put("params", params)
        // A session outlives neither a restart of Home Assistant nor its own idle timeout, and the
        // gap between listing the tools and calling one is however long until someone speaks.
        val answer = post(body) ?: run {
            initialized = false
            session = null
            if (!handshake()) return null
            post(body)
        } ?: return null
        answer.optJSONObject("error")?.let {
            Log.w(TAG, "mcp $method: $it")
            return null
        }
        return answer.optJSONObject("result")
    }

    private fun handshake(): Boolean {
        val initialize = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", ++lastId)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put("protocolVersion", PROTOCOL)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "hachi").put("version", "1")),
            )
        val hello = post(initialize) ?: return false
        Log.i(TAG, "mcp: ${hello.optJSONObject("result")?.optJSONObject("serverInfo")}")
        // The server is entitled to refuse everything else until this arrives.
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        initialized = true
        return true
    }

    /** Sends one message and returns the one that came back, or null for both failure and silence. */
    private fun post(message: JSONObject): JSONObject? = try {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json, text/event-stream")
            .apply { session?.let { addHeader("Mcp-Session-Id", it) } }
            .post(message.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "mcp ${message.optString("method")} failed (${response.code}): ${body.take(200)}")
                return null
            }
            response.header("Mcp-Session-Id")?.let { session = it }
            mcpBody(body)
        }
    } catch (e: Exception) {
        Log.w(TAG, "mcp ${message.optString("method")} failed", e)
        null
    }

    companion object {
        private const val TAG = "Hachi"

        // One client for the whole app: the registry and the house page each hold their own server,
        // and both are rebuilt whenever settings might have changed.
        private val http = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        private const val PROTOCOL = "2025-06-18"
        private val JSON = "application/json".toMediaType()

        /** Null until both the address of the house and a token for it have been set. */
        fun from(settings: Settings): McpServer? {
            val endpoint = mcpEndpoint(settings.homeAssistantUrl)
            val token = settings.homeAssistantToken
            return if (endpoint.isBlank() || token.isBlank()) null else McpServer(endpoint, token)
        }
    }
}

/** One tool the house offers. The declaration is the server's, only reshaped for Gemini. */
class McpTool(private val server: McpServer, override val declaration: JSONObject) : Tool {
    override val name: String = declaration.optString("name")
    override fun run(args: JSONObject): JSONObject = server.call(name, args)
}

/**
 * Accepts either the MCP endpoint itself or just the address of the Home Assistant instance.
 *
 * ponytail: the default path is what the design notes say and has not been confirmed against a real
 * instance yet; pasting the full endpoint in Settings works whatever it turns out to be.
 */
fun mcpEndpoint(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    // "http://host:8123" has two slashes; anything more already carries a path.
    return if (trimmed.count { it == '/' } > 2) trimmed else "$trimmed/api/mcp"
}

/**
 * The JSON-RPC message in an HTTP response body.
 *
 * Streamable HTTP lets the server answer either with plain JSON or with a one-message SSE stream,
 * and which one arrives is its choice, not ours.
 */
fun mcpBody(body: String): JSONObject? {
    val text = body.trim()
    if (text.isEmpty()) return null
    val json = if (text.startsWith("{")) {
        text
    } else {
        text.lineSequence().lastOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
            ?: return null
    }
    return runCatching { JSONObject(json) }.getOrNull()
}

/** The declarations to offer Gemini, from the `tools` array of a `tools/list` result. */
fun mcpDeclarations(tools: JSONArray): List<JSONObject> =
    (0 until tools.length()).mapNotNull { mcpDeclaration(tools.optJSONObject(it)) }

private val NAME = Regex("[a-zA-Z_][a-zA-Z0-9_.\\-]{0,63}")

/** Null when the tool cannot be offered at all -- a name Gemini would reject fails the whole setup. */
fun mcpDeclaration(tool: JSONObject?): JSONObject? {
    if (tool == null) return null
    val name = tool.optString("name")
    if (!NAME.matches(name)) return null
    val description = tool.optString("description").ifBlank { name }
    val declaration = declare(name, description)
    val parameters = geminiSchema(tool.optJSONObject("inputSchema"))
    // A tool that takes nothing must say so by leaving the parameters out; an empty object is not
    // the same thing to Gemini.
    if ((parameters?.optJSONObject("properties")?.length() ?: 0) > 0) {
        declaration.put("parameters", parameters!!)
    }
    return declaration
}

/**
 * A JSON Schema rebuilt as the OpenAPI subset Gemini accepts.
 *
 * Rebuilt rather than filtered: an MCP server may put anything in there ($schema, additionalProperties,
 * title, default), and one field Gemini does not know rejects the setup message -- which would take
 * the weather and the clock down with the house.
 */
fun geminiSchema(raw: JSONObject?): JSONObject? {
    if (raw == null) return null
    val type = schemaType(raw)
    // A union of shapes: Gemini has no equivalent, so the first branch is the one we offer. What
    // the argument means is written on the union itself, not on the branch, and losing it leaves the
    // model an argument it cannot tell the purpose of.
    if (type == null) {
        val branch = raw.optJSONArray("anyOf") ?: raw.optJSONArray("oneOf")
        val chosen = geminiSchema(branch?.optJSONObject(0)) ?: return null
        if (!chosen.has("description")) {
            raw.optString("description").takeIf { it.isNotBlank() }?.let { chosen.put("description", it) }
        }
        return chosen
    }
    val out = JSONObject().put("type", type)
    raw.optString("description").takeIf { it.isNotBlank() }?.let { out.put("description", it) }
    raw.optJSONArray("enum")?.takeIf { it.length() > 0 && type == "string" }?.let { out.put("enum", it) }
    if (type == "array") {
        out.put("items", geminiSchema(raw.optJSONObject("items")) ?: JSONObject().put("type", "string"))
    }
    if (type == "object") {
        val properties = JSONObject()
        val source = raw.optJSONObject("properties")
        source?.keys()?.forEach { key ->
            geminiSchema(source.optJSONObject(key))?.let { properties.put(key, it) }
        }
        out.put("properties", properties)
        val required = JSONArray()
        raw.optJSONArray("required")?.let { names ->
            for (i in 0 until names.length()) {
                val name = names.optString(i)
                if (properties.has(name)) required.put(name)
            }
        }
        if (required.length() > 0) out.put("required", required)
    }
    return out
}

/** Null when there is nothing to go on, so the caller can look for a union instead. */
private fun schemaType(raw: JSONObject): String? {
    val declared = when (val type = raw.opt("type")) {
        is String -> type
        // ["string", "null"]: optional, which Gemini says with required instead.
        is JSONArray -> (0 until type.length()).map { type.optString(it) }.firstOrNull { it != "null" }
        else -> null
    } ?: when {
        raw.has("properties") -> "object"
        raw.has("items") -> "array"
        raw.has("enum") -> "string"
        else -> return null
    }
    return when (declared.lowercase()) {
        "object", "array", "integer", "number", "boolean" -> declared.lowercase()
        else -> "string" // including the ones with no equivalent, which read fine as text
    }
}

/** What a `tools/call` result says, in the shape the model is given it back. */
fun mcpResult(result: JSONObject): JSONObject {
    val content = result.optJSONArray("content")
    val text = buildString {
        for (i in 0 until (content?.length() ?: 0)) {
            val part = content?.optJSONObject(i) ?: continue
            val piece = part.optString("text")
            if (piece.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(piece)
            }
        }
    }
    if (result.optBoolean("isError")) return failure(text.ifBlank { "house_call_failed" })
    return JSONObject().put("result", text.ifBlank { "done" })
}
