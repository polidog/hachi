package dev.polidog.hachi

import dev.polidog.hachi.tools.geminiSchema
import dev.polidog.hachi.tools.mcpBody
import dev.polidog.hachi.tools.mcpDeclaration
import dev.polidog.hachi.tools.mcpDeclarations
import dev.polidog.hachi.tools.mcpEndpoint
import dev.polidog.hachi.tools.mcpResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the house says, as Hachi has to read it.
 *
 * Unlike the weather payloads, these are written from the MCP specification and Home Assistant's
 * intent schemas rather than captured from a running instance -- step 1 of the plan (curl against
 * the real server) has not been done yet. Replace them with the real `tools/list` once it has.
 */
class McpServerTest {
    private val toolsList = """
        {"jsonrpc":"2.0","id":2,"result":{"tools":[
          {"name":"HassTurnOn","description":"Turns on/opens a device or entity",
           "inputSchema":{"type":"object","properties":{
             "name":{"type":"string"},
             "area":{"type":"string"},
             "floor":{"type":"string"},
             "domain":{"type":"array","items":{"type":"string"}},
             "device_class":{"type":"array","items":{"type":"string",
               "enum":["awning","blind","curtain","garage"]}}},
            "required":[],"additionalProperties":false,
            "${'$'}schema":"https://json-schema.org/draft/2020-12/schema"}},
          {"name":"HassMediaSearchAndPlay","description":"Searches for media and plays the first result",
           "inputSchema":{"type":"object","properties":{
             "search_query":{"type":"string"},
             "media_class":{"anyOf":[{"type":"string"},{"type":"null"}]},
             "name":{"type":"string","title":"Name"}},
            "required":["search_query"]}},
          {"name":"GetLiveContext","description":"Use this tool when the user asks about the current state of the house",
           "inputSchema":{"type":"object","properties":{},"additionalProperties":false}}
        ]}}
    """.trimIndent()

    @Test fun `every house tool becomes a declaration`() {
        val tools = JSONObject(toolsList).getJSONObject("result").getJSONArray("tools")
        val names = mcpDeclarations(tools).map { it.getString("name") }
        assertEquals(listOf("HassTurnOn", "HassMediaSearchAndPlay", "GetLiveContext"), names)
    }

    @Test fun `json schema is rebuilt as the subset Gemini accepts`() {
        val tools = JSONObject(toolsList).getJSONObject("result").getJSONArray("tools")
        val parameters = mcpDeclarations(tools)[0].getJSONObject("parameters")

        assertEquals("object", parameters.getString("type"))
        // Everything Gemini does not know is gone, not passed through.
        assertFalse(parameters.has("additionalProperties"))
        assertFalse(parameters.has("\$schema"))

        val properties = parameters.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("name").getString("type"))
        val deviceClass = properties.getJSONObject("device_class")
        assertEquals("array", deviceClass.getString("type"))
        assertEquals("string", deviceClass.getJSONObject("items").getString("type"))
        assertEquals(4, deviceClass.getJSONObject("items").getJSONArray("enum").length())
        // An empty required list is left out entirely; Gemini rejects the empty array.
        assertFalse(parameters.has("required"))
    }

    @Test fun `a required argument survives, and a union picks its first branch`() {
        val tools = JSONObject(toolsList).getJSONObject("result").getJSONArray("tools")
        val parameters = mcpDeclarations(tools)[1].getJSONObject("parameters")
        assertEquals("search_query", parameters.getJSONArray("required").getString(0))
        assertEquals("string", parameters.getJSONObject("properties").getJSONObject("media_class").getString("type"))
        // "title" is JSON Schema's, not Gemini's.
        assertFalse(parameters.getJSONObject("properties").getJSONObject("name").has("title"))
    }

    @Test fun `a tool that takes nothing is declared without parameters`() {
        val tools = JSONObject(toolsList).getJSONObject("result").getJSONArray("tools")
        assertFalse(mcpDeclarations(tools)[2].has("parameters"))
    }

    @Test fun `a name Gemini would reject takes only that tool out`() {
        assertNull(mcpDeclaration(JSONObject("""{"name":"turn on!","description":"x"}""")))
        assertNull(mcpDeclaration(JSONObject("""{"description":"nameless"}""")))
    }

    @Test fun `required names that lost their property are dropped with it`() {
        val schema = geminiSchema(
            JSONObject("""{"type":"object","properties":{"a":{"type":"string"},"b":{}},"required":["a","b"]}""")
        )!!
        assertFalse(schema.getJSONObject("properties").has("b"))
        assertEquals(1, schema.getJSONArray("required").length())
    }

    @Test fun `a result is the text the house answered with`() {
        val result = JSONObject(
            """{"content":[{"type":"text","text":"Turned on the light"}],"isError":false}"""
        )
        assertEquals("Turned on the light", mcpResult(result).getString("result"))
    }

    @Test fun `a failed call is reported to the model as an error`() {
        val result = JSONObject(
            """{"content":[{"type":"text","text":"MatchFailedError: no entity named couch"}],"isError":true}"""
        )
        assertTrue(mcpResult(result).getString("error").contains("MatchFailedError"))
    }

    @Test fun `an answer framed as an event stream is read the same as plain json`() {
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}\n\n"
        assertTrue(mcpBody(sse)!!.getJSONObject("result").has("tools"))
        assertTrue(mcpBody("""{"jsonrpc":"2.0","id":1,"result":{}}""")!!.has("result"))
        // A notification is answered with nothing at all.
        assertNull(mcpBody(""))
    }

    @Test fun `the address may be the instance or the endpoint itself`() {
        assertEquals("http://homeassistant.local:8123/api/mcp", mcpEndpoint("http://homeassistant.local:8123/"))
        assertEquals("http://10.0.1.5:8123/mcp_server/mcp", mcpEndpoint(" http://10.0.1.5:8123/mcp_server/mcp "))
        assertEquals("", mcpEndpoint("  "))
    }
}
