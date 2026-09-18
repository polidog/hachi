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
 * Captured with curl from the Home Assistant at home (2026.9.2, MCP server 1.26.0): four of the 21
 * tools it offers, picked for the shapes that have to survive the trip to Gemini -- an array of
 * enums, an `anyOf`, bounds Gemini has no use for, and a description worth keeping.
 */
class McpServerTest {
    private val toolsList = """
        {
          "jsonrpc": "2.0",
          "id": 2,
          "result": {
            "tools": [
              {
                "name": "intent__HassTurnOn",
                "description": "Turns on/opens/presses a device or entity. For locks, this performs a 'lock' action. Use for requests like 'turn on', 'activate', 'enable', or 'lock'.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string"
                    },
                    "area": {
                      "type": "string"
                    },
                    "floor": {
                      "type": "string"
                    },
                    "domain": {
                      "type": "array",
                      "items": {
                        "type": "string"
                      }
                    },
                    "device_class": {
                      "type": "array",
                      "items": {
                        "type": "string",
                        "enum": [
                          "outlet",
                          "switch",
                          "identify",
                          "restart",
                          "update",
                          "awning",
                          "blind",
                          "curtain",
                          "damper",
                          "door",
                          "garage",
                          "gate",
                          "shade",
                          "shutter",
                          "window",
                          "tv",
                          "speaker",
                          "receiver",
                          "projector",
                          "water",
                          "gas"
                        ]
                      }
                    }
                  }
                }
              },
              {
                "name": "media_player__HassMediaSearchAndPlay",
                "description": "Searches for media and plays the first result",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "search_query": {
                      "type": "string"
                    },
                    "media_class": {
                      "type": "string",
                      "enum": [
                        "album",
                        "app",
                        "artist",
                        "channel",
                        "composer",
                        "contributing_artist",
                        "directory",
                        "episode",
                        "game",
                        "genre",
                        "image",
                        "movie",
                        "music",
                        "playlist",
                        "podcast",
                        "season",
                        "track",
                        "tv_show",
                        "url",
                        "video"
                      ]
                    },
                    "name": {
                      "type": "string"
                    },
                    "area": {
                      "type": "string"
                    },
                    "floor": {
                      "type": "string"
                    }
                  }
                }
              },
              {
                "name": "homeassistant__GetLiveContext",
                "description": "Provides real-time information about the CURRENT state, value, or mode of devices, sensors, entities, or areas. Use this tool for: 1. Answering questions about current conditions (e.g., 'Is the light on?'). 2. As the first step in conditional actions (e.g., 'If the weather is rainy, turn off sprinklers' requires checking the weather first). You may filter for devices by name, domain, and area, including combining those filters. Prefer filtering by domain when searching for multiple devices of the same type.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "Filter entities by name or alias (case-insensitive)."
                    },
                    "domain": {
                      "anyOf": [
                        {
                          "type": "string"
                        },
                        {
                          "type": "array",
                          "items": {
                            "type": "string"
                          }
                        }
                      ],
                      "description": "Filter entities by domain (e.g. 'light', 'sensor'). Accepts a single domain or a list."
                    },
                    "area": {
                      "type": "string",
                      "description": "Filter entities by area name or alias (case-insensitive)."
                    }
                  }
                }
              },
              {
                "name": "light__HassLightSet",
                "description": "Sets the brightness percentage or color of a light",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string"
                    },
                    "area": {
                      "type": "string"
                    },
                    "floor": {
                      "type": "string"
                    },
                    "domain": {
                      "type": "array",
                      "items": {
                        "type": "string",
                        "enum": [
                          "light"
                        ]
                      }
                    },
                    "color": {
                      "type": "string"
                    },
                    "temperature": {
                      "type": "integer",
                      "minimum": 0
                    },
                    "brightness": {
                      "type": "integer",
                      "minimum": 0,
                      "maximum": 100,
                      "description": "The brightness percentage of the light between 0 and 100, where 0 is off and 100 is fully lit"
                    }
                  }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val tools get() = JSONObject(toolsList).getJSONObject("result").getJSONArray("tools")

    @Test fun `every house tool becomes a declaration`() {
        assertEquals(
            listOf(
                "intent__HassTurnOn",
                "media_player__HassMediaSearchAndPlay",
                "homeassistant__GetLiveContext",
                "light__HassLightSet",
            ),
            mcpDeclarations(tools).map { it.getString("name") },
        )
    }

    @Test fun `an array of enums keeps both the array and the values`() {
        val properties = mcpDeclarations(tools)[0].getJSONObject("parameters").getJSONObject("properties")
        val deviceClass = properties.getJSONObject("device_class")
        assertEquals("array", deviceClass.getString("type"))
        val items = deviceClass.getJSONObject("items")
        assertEquals("string", items.getString("type"))
        assertEquals(21, items.getJSONArray("enum").length())
    }

    @Test fun `home assistant marks nothing as required, so nothing is sent as required`() {
        // Its intents take every argument optionally and match on whatever arrives.
        assertFalse(mcpDeclarations(tools)[0].getJSONObject("parameters").has("required"))
    }

    @Test fun `a union of a string and a list of strings is offered as the string`() {
        val properties = mcpDeclarations(tools)[2].getJSONObject("parameters").getJSONObject("properties")
        val domain = properties.getJSONObject("domain")
        assertEquals("string", domain.getString("type"))
        assertTrue(domain.getString("description").startsWith("Filter entities by domain"))
    }

    @Test fun `bounds are dropped and the description that explains them is kept`() {
        val properties = mcpDeclarations(tools)[3].getJSONObject("parameters").getJSONObject("properties")
        val brightness = properties.getJSONObject("brightness")
        assertEquals("integer", brightness.getString("type"))
        assertFalse(brightness.has("minimum"))
        assertFalse(brightness.has("maximum"))
        assertTrue(brightness.getString("description").contains("between 0 and 100"))
    }

    @Test fun `json schema keywords Gemini does not know are left behind`() {
        // Home Assistant sends none of these, but one field Gemini cannot parse rejects the whole
        // setup message -- which would take the clock and the weather down with the house.
        val schema = geminiSchema(
            JSONObject(
                """
                {"type":"object","title":"Turn on","additionalProperties":false,
                 "properties":{"name":{"type":"string","default":"","examples":["lamp"]},"junk":{}},
                 "required":["name","junk"]}
                """.trimIndent()
            )
        )!!
        assertEquals(setOf("type", "properties", "required"), schema.keys().asSequence().toSet())
        val name = schema.getJSONObject("properties").getJSONObject("name")
        assertEquals(setOf("type"), name.keys().asSequence().toSet())
        // A property that could not be translated takes its entry in required with it.
        assertFalse(schema.getJSONObject("properties").has("junk"))
        assertEquals(1, schema.getJSONArray("required").length())
    }

    @Test fun `a tool that takes nothing is declared without parameters`() {
        val tool = JSONObject("""{"name":"ping","description":"x","inputSchema":{"type":"object","properties":{}}}""")
        assertFalse(mcpDeclaration(tool)!!.has("parameters"))
    }

    @Test fun `a name Gemini would reject takes only that tool out`() {
        assertNull(mcpDeclaration(JSONObject("""{"name":"turn on!","description":"x"}""")))
        assertNull(mcpDeclaration(JSONObject("""{"description":"nameless"}""")))
    }

    @Test fun `a result is the text the house answered with`() {
        val result = JSONObject(
            """
                {
                  "content": [
                    {
                      "type": "text",
                      "text": "{\"success\": true, \"result\": \"Live Context: An overview of the areas and the devices in this smart home:\\n- names: カウンター照明1\\n  domain: light\\n  state: 'on'\\n  areas: 台所\\n\"}"
                    }
                  ],
                  "isError": false
                }
            """.trimIndent()
        )
        assertTrue(mcpResult(result).getString("result").contains("カウンター照明1"))
    }

    @Test fun `a call the house could not match is reported to the model as an error`() {
        val result = JSONObject(
            """
                {
                  "content": [
                    {
                      "type": "text",
                      "text": "Error calling tool: <MatchFailedError result=MatchTargetsResult(is_match=False, no_match_reason=..."
                    }
                  ],
                  "isError": true
                }
            """.trimIndent()
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
        assertEquals("http://192.168.1.102:8123/api/mcp", mcpEndpoint("http://192.168.1.102:8123/"))
        assertEquals("http://192.168.1.102:8123/mcp_server/sse", mcpEndpoint(" http://192.168.1.102:8123/mcp_server/sse "))
        assertEquals("", mcpEndpoint("  "))
    }
}
