package dev.polidog.hachi

import dev.polidog.hachi.tools.JEV_NONE
import dev.polidog.hachi.tools.choose
import dev.polidog.hachi.tools.jevPick
import dev.polidog.hachi.tools.jevRequest
import dev.polidog.hachi.tools.renamed
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking Jev which device a misheard name meant, and trusting only a clear answer. */
class JevNamesTest {
    private fun answer(choice: String, confidence: Double, odds: String = "{}") =
        """{"model":"jev-latest","answers":{"device":{"type":"choice","choice":"$choice",
            "probabilities":$odds,"confidence":$confidence}},"usage":{}}"""

    @Test
    fun oneOptionPerNameWithWhereAndWhatKind() {
        val devices = listOf(
            Device("書斎エアコン", "climate", "off", "書斎"),
            Device("書斎エアコン", "switch", "unknown", "書斎"),
            Device("リビング照明", "light", "on", "リビング"),
        )
        val criteria = jevRequest(JSONObject().put("name", "書斎のエアコン"), devices)
            .getJSONObject("questions").getJSONObject("device").getJSONObject("criteria")
        assertEquals(setOf("書斎エアコン", "リビング照明", JEV_NONE), criteria.keys().asSequence().toSet())
        assertEquals("書斎 climate / 書斎 switch", criteria.getString("書斎エアコン"))
    }

    @Test
    fun takesAClearChoice() = assertEquals("書斎エアコン", jevPick(answer("書斎エアコン", 0.9))?.sure)

    @Test
    fun refusesNoneAnUnsureAnswerAndAnError() {
        assertNull(jevPick(answer(JEV_NONE, 1.0))?.sure)
        assertNull(jevPick(answer("書斎エアコン", 0.2))?.sure)
        assertNull(jevPick("""{"detail":"invalid"}"""))
    }

    @Test
    fun anUnsureAnswerStillNamesTheLikelyFewMostLikelyFirst() {
        val odds = """{"書斎照明":0.3,"車庫照明":0.4,"トイレ照明":0.01,"$JEV_NONE":0.29}"""
        assertEquals(listOf("車庫照明", "書斎照明"), jevPick(answer("車庫照明", 0.4, odds))?.maybe)
    }

    @Test
    fun theChoicesGoBackNumberedAndAsAList() {
        val asked = choose(listOf("車庫照明", "書斎照明"))
        assertTrue(asked.getString("error").contains("1. 車庫照明, 2. 書斎照明"))
        assertEquals("書斎照明", asked.getJSONArray("choices").getString(1))
    }

    @Test
    fun aRenamedCallSaysWhatKindTheHouseHasAndNeverWhere() {
        val aircon = Device("書斎エアコン", "climate", "off", "書斎")
        val bare = renamed(JSONObject().put("name", "書斎のエアコン"), aircon)
        assertEquals("書斎エアコン", bare.getString("name"))
        assertEquals("climate", bare.getJSONArray("domain").getString(0))
        assertEquals(false, bare.has("area"))
        val said = renamed(JSONObject().put("name", "書斎のエアコン").put("area", "2階"), aircon)
        assertEquals(false, said.has("area"))
        val nowhere = Device("書斎照明", "switch", "on", "書斎")
        val room = renamed(JSONObject().put("domain", JSONArray().put("light")).put("area", "書斎"), nowhere)
        assertEquals("書斎照明", room.getString("name"))
        assertEquals("switch", room.getJSONArray("domain").getString(0))
        assertEquals(false, room.has("area"))
    }
}
