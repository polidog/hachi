package dev.polidog.hachi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTest {
    private val rates = mapOf("AUDIO" to 3.0, "TEXT" to 0.75)

    private fun details(modality: String, tokens: Long) =
        JSONArray().put(JSONObject().put("modality", modality).put("tokenCount", tokens))

    @Test
    fun chargesOnlyForTokensAddedSinceTheLastReport() {
        val baseline = HashMap<String, Long>()
        assertEquals(3.0, newSpend(baseline, "prompt", details("AUDIO", 1_000_000), rates), 1e-9)
        // The same running total arriving again is not new spend.
        assertEquals(0.0, newSpend(baseline, "prompt", details("AUDIO", 1_000_000), rates), 1e-9)
        assertEquals(1.5, newSpend(baseline, "prompt", details("AUDIO", 1_500_000), rates), 1e-9)
        // A count that went backwards means a fresh session, so all of it is new.
        assertEquals(0.6, newSpend(baseline, "prompt", details("AUDIO", 200_000), rates), 1e-9)
    }

    @Test
    fun pricesEachModalityAtItsOwnRateAndTracksThemSeparately() {
        val baseline = HashMap<String, Long>()
        val mixed = JSONArray()
            .put(JSONObject().put("modality", "AUDIO").put("tokenCount", 1_000_000))
            .put(JSONObject().put("modality", "TEXT").put("tokenCount", 1_000_000))
        assertEquals(3.75, newSpend(baseline, "prompt", mixed, rates), 1e-9)
        // Growth in one modality must not re-charge the other.
        assertEquals(0.75, newSpend(baseline, "prompt", details("TEXT", 2_000_000), rates), 1e-9)
    }

    @Test
    fun missingOrUnknownDetailsCostNothingExtra() {
        val baseline = HashMap<String, Long>()
        assertEquals(0.0, newSpend(baseline, "prompt", null, rates), 1e-9)
        // An unrecognised modality falls back to the text rate rather than crashing.
        assertEquals(0.75, newSpend(baseline, "prompt", details("SOMETHING_NEW", 1_000_000), rates), 1e-9)
    }
}
