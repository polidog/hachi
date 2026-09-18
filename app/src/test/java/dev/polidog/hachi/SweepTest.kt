package dev.polidog.hachi

import dev.polidog.hachi.tools.sweeps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A switch with nowhere said is held back; one aimed at a room or a name goes through. */
class SweepTest {
    private val lights = JSONObject().put("domain", JSONArray().put("light"))

    @Test
    fun everyLightIsASweep() = assertTrue(sweeps("intent__HassTurnOff", lights))

    @Test
    fun aRoomOrANameIsNot() {
        assertFalse(sweeps("intent__HassTurnOff", JSONObject(lights.toString()).put("area", "書斎")))
        assertFalse(sweeps("intent__HassTurnOff", JSONObject().put("name", "書斎照明")))
    }

    @Test
    fun readingIsNot() = assertFalse(sweeps("homeassistant__GetLiveContext", JSONObject()))
}
