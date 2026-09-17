package dev.polidog.hachi.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing Hachi can do on the model's behalf.
 *
 * Tools know nothing about the UI: they take the arguments the model sent and return a JSON result.
 * Anything that should change what is on screen is decided by the caller from the result.
 */
interface Tool {
    val name: String

    /** The Gemini function declaration, as its OpenAPI-flavoured schema. */
    val declaration: JSONObject

    /** Blocking -- tools do network I/O. Never called on the main thread. */
    fun run(args: JSONObject): JSONObject
}

/** Builds a declaration with no arguments. */
fun declare(name: String, description: String): JSONObject =
    JSONObject().put("name", name).put("description", description)

/** Builds a declaration whose parameters are all plain strings. */
fun declare(
    name: String,
    description: String,
    parameters: List<Triple<String, String, Boolean>>, // (name, description, required)
): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    for ((param, text, isRequired) in parameters) {
        properties.put(param, JSONObject().put("type", "string").put("description", text))
        if (isRequired) required.put(param)
    }
    val schema = JSONObject().put("type", "object").put("properties", properties)
    if (required.length() > 0) schema.put("required", required)
    return declare(name, description).put("parameters", schema)
}

/** The shape every tool uses to report a failure the model should tell the user about. */
fun failure(reason: String): JSONObject = JSONObject().put("error", reason)
