package dev.polidog.hachi

import dev.polidog.hachi.tools.climateApiBase
import dev.polidog.hachi.tools.climateStates
import dev.polidog.hachi.tools.withClimateDetails
import org.junit.Assert.*
import org.junit.Test

class ClimateControlsTest {
    // Contract edge cases: fractional targets, a range thermostat, and a null current reading.
    private val response = """
        {"unit":"°C","items":[
          {"entity_id":"climate.bedroom","name":"AC","area":"Bedroom","state":"cool",
           "attributes":{"hvac_modes":["off","cool","heat_cool"],"temperature":21.5,
             "current_temperature":null,"min_temp":16,"max_temp":30,"target_temp_step":0.5,
             "supported_features":3,"target_temp_low":20,"target_temp_high":24}},
          {"entity_id":"climate.study","name":"AC","area":"Study","state":"off",
           "attributes":{"hvac_modes":["off","heat"],"temperature":null,"supported_features":1}}
        ]}
    """.trimIndent()

    @Test fun `capabilities retain fractional temperatures and missing readings`() {
        val states = climateStates(response)
        val ac = states.first()
        assertEquals(21.5, ac.temperature!!, 0.0)
        assertNull(ac.current)
        assertEquals(listOf("off", "cool", "heat_cool"), ac.modes)
        assertEquals(20.0, ac.low!!, 0.0)
        assertEquals(24.0, ac.high!!, 0.0)
        assertEquals(22.0, ac.shifted(21.5, 1)!!, 0.0)
        assertEquals(16.0, ac.shifted(16.0, -1)!!, 0.0)
        assertEquals(30.0, ac.shifted(30.0, 1)!!, 0.0)
        assertNull(states.last().temperature)
        assertNull(states.last().shifted(21.0, 1))
    }

    @Test fun `same named devices in other rooms do not collapse or receive each others controls`() {
        val devices = listOf(Device("AC", "climate", "off", "Bedroom"),
            Device("AC", "climate", "off", "Study"), Device("AC", "switch", "unknown", "Bedroom"))
        val result = withClimateDetails(tiles(devices), climateStates(response))
        assertEquals(2, result.size)
        assertEquals("climate.bedroom", result[0].climate?.entityId)
        assertEquals("climate.study", result[1].climate?.entityId)
        assertNotEquals(result[0].key, result[1].key)
    }

    @Test fun `ambiguous or unmatched names never acquire another entity id`() {
        val states = climateStates(response)
        val ac = states.first()
        val devices = listOf(Device("AC", "climate", "off", "Bedroom"),
            Device("AC", "climate", "off", ""), Device("Alias", "climate", "off", "Study"))
        val result = withClimateDetails(devices, states + ac.copy(entityId = "climate.other"))
        assertTrue(result.all { it.climate == null })
    }

    @Test fun `base and MCP URLs resolve to the same API including proxy prefixes`() {
        assertEquals("http://ha:8123/api", climateApiBase(" http://ha:8123/ "))
        assertEquals("http://ha:8123/api", climateApiBase("http://ha:8123/api/mcp/"))
        assertEquals("https://ha/home/api", climateApiBase("https://ha/home/api/mcp"))
        assertEquals("", climateApiBase("  "))
    }

    @Test fun `the dial snaps to the thermostat's own steps and never leaves its range`() {
        assertEquals(16.0, dialValue(0f, 16.0, 30.0, 0.5), 0.0)
        assertEquals(30.0, dialValue(1f, 16.0, 30.0, 0.5), 0.0)
        assertEquals(23.0, dialValue(0.5f, 16.0, 30.0, 0.5), 0.0)
        // 16 + 14 * 0.52 = 23.28, which a half-degree thermostat can only take as 23.5.
        assertEquals(23.5, dialValue(0.52f, 16.0, 30.0, 0.5), 0.0)
        // Steps count from the bottom of the range, not from zero.
        assertEquals(16.7, dialValue(0.04f, 16.2, 30.0, 0.5), 1e-9)
        assertEquals(30.0, dialValue(1.4f, 16.0, 30.0, 1.0), 0.0)
    }
}
