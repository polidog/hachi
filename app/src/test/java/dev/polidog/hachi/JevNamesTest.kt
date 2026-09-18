package dev.polidog.hachi

import dev.polidog.hachi.tools.JEV_NONE
import dev.polidog.hachi.tools.jevChoice
import dev.polidog.hachi.tools.jevRequest
import dev.polidog.hachi.tools.renamed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Asking Jev which device a misheard name meant, and trusting only a clear answer. */
class JevNamesTest {
    private fun answer(choice: String, confidence: Double) =
        """{"model":"jev-latest","answers":{"device":{"type":"choice","choice":"$choice",
            "probabilities":{},"confidence":$confidence}},"usage":{}}"""

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
    fun takesAClearChoice() = assertEquals("書斎エアコン", jevChoice(answer("書斎エアコン", 0.9)))

    @Test
    fun refusesNoneAnUnsureAnswerAndAnError() {
        assertNull(jevChoice(answer(JEV_NONE, 1.0)))
        assertNull(jevChoice(answer("書斎エアコン", 0.2)))
        assertNull(jevChoice("""{"detail":"invalid"}"""))
    }

    @Test
    fun aRenamedCallSaysWhereAndWhatKindUnlessTheModelAlreadyDid() {
        val aircon = Device("書斎エアコン", "climate", "off", "書斎")
        val bare = renamed(JSONObject().put("name", "書斎のエアコン"), aircon)
        assertEquals("書斎エアコン", bare.getString("name"))
        assertEquals("climate", bare.getJSONArray("domain").getString(0))
        assertEquals("書斎", bare.getString("area"))
        val said = renamed(JSONObject().put("name", "書斎のエアコン").put("area", "2階"), aircon)
        assertEquals("2階", said.getString("area"))
    }
}
