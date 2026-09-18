package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a `GetLiveContext` answer into tiles.
 *
 * The listing below is the shape a real Home Assistant answers with: the intent's JSON wrapper, one
 * item per entity, and the nested `attributes` block a sensor brings with it.
 */
class HouseTest {
    private val answer = """
        {"success": true, "result": "Live Context: An overview of the areas and the devices in this smart home:\n- names: カウンター照明1\n  domain: light\n  state: 'on'\n  areas: 台所\n- names: 玄関, エントランス\n  domain: light\n  state: 'off'\n  areas: 玄関\n- names: 居間の温度\n  domain: sensor\n  state: '23.4'\n  areas: 居間\n  attributes:\n    unit_of_measurement: °C\n    domain: not_a_device\n- names: 寝室のエアコン\n  domain: climate\n  state: fan_only\n  areas: 寝室\n  attributes:\n    current_temperature:\n    temperature: '21'\n- names: 寝室のエアコン\n  domain: switch\n  state: unknown\n  areas: 寝室\n  attributes:\n    device_class: switch\n"}
    """.trimIndent()

    @Test fun `every item in the listing becomes a device`() {
        val devices = liveContext(answer)
        assertEquals(
            listOf("カウンター照明1", "玄関", "居間の温度", "寝室のエアコン", "寝室のエアコン"),
            devices.map { it.name },
        )
        assertEquals(Device("カウンター照明1", "light", "on", "台所"), devices[0])
    }

    @Test fun `a nested block belongs to its item, not to the next one`() {
        val sensor = liveContext(answer).first { it.name == "居間の温度" }
        // The nested `domain: not_a_device` would otherwise have become the item's own.
        assertEquals("sensor", sensor.domain)
        assertEquals("23.4", sensor.state)
        assertNull(sensor.setpoint)
    }

    @Test fun `only what a tile can work is offered as one`() {
        val devices = tiles(liveContext(answer))
        assertEquals(listOf("カウンター照明1", "玄関", "寝室のエアコン"), devices.map { it.name })
        assertTrue(devices[0].isOn)
        assertFalse(devices[1].isOn)
    }

    @Test fun `an air conditioner is the thermostat, not the switch of the same name`() {
        val ac = tiles(liveContext(answer)).last()
        assertEquals("climate", ac.domain)
        assertEquals(21.0, ac.setpoint)
        // Every mode but `off` is on, and the switch twin's `unknown` would have read as off.
        assertTrue(ac.isOn)
    }

    @Test fun `a room's air is read off its sensors`() {
        val sensor = liveContext(answer).first { it.name == "居間の温度" }
        assertTrue(sensor.measures("temperature"))
        assertFalse(sensor.measures("humidity"))
        assertTrue(Device("寝室の湿度", "sensor", "48", "寝室", unit = "%").measures("humidity"))
        assertTrue(Device("Bedroom", "sensor", "48", "寝室", unit = "%", kind = "humidity").measures("humidity"))
        // A battery is a percentage too, and an unavailable thermometer reads nothing.
        assertFalse(Device("電池", "sensor", "80", "寝室", unit = "%", kind = "battery").measures("humidity"))
        assertFalse(Device("温度", "sensor", "unavailable", "寝室", unit = "°C").measures("temperature"))
    }

    @Test fun `an answer that is not the house leaves no tiles behind`() {
        assertTrue(liveContext("").isEmpty())
        assertTrue(liveContext("Error calling tool: MatchFailedError").isEmpty())
    }
}
