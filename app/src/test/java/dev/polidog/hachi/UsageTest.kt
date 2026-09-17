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
        assertEquals(3.0, newSpend(baseline, "prompt", 1_000_000, details("AUDIO", 1_000_000), rates), 1e-9)
        // The same running total arriving again is not new spend.
        assertEquals(0.0, newSpend(baseline, "prompt", 1_000_000, details("AUDIO", 1_000_000), rates), 1e-9)
        assertEquals(1.5, newSpend(baseline, "prompt", 1_500_000, details("AUDIO", 1_500_000), rates), 1e-9)
        // A count that went backwards means a fresh session, so all of it is new.
        assertEquals(0.6, newSpend(baseline, "prompt", 200_000, details("AUDIO", 200_000), rates), 1e-9)
    }

    @Test
    fun pricesEachModalityAtItsOwnRateAndTracksThemSeparately() {
        val baseline = HashMap<String, Long>()
        val mixed = JSONArray()
            .put(JSONObject().put("modality", "AUDIO").put("tokenCount", 1_000_000))
            .put(JSONObject().put("modality", "TEXT").put("tokenCount", 1_000_000))
        assertEquals(3.75, newSpend(baseline, "prompt", 2_000_000, mixed, rates), 1e-9)
        // Growth in one modality must not re-charge the other. Every report carries the full
        // breakdown, so the unchanged AUDIO line is repeated here as the API repeats it.
        val grown = JSONArray()
            .put(JSONObject().put("modality", "AUDIO").put("tokenCount", 1_000_000))
            .put(JSONObject().put("modality", "TEXT").put("tokenCount", 2_000_000))
        assertEquals(0.75, newSpend(baseline, "prompt", 3_000_000, grown, rates), 1e-9)
    }

    @Test
    fun billsTheTokensTheBreakdownLeavesOut() {
        val baseline = HashMap<String, Long>()
        // A real report: 599 prompt tokens, of which only 351 TEXT + 221 AUDIO were broken out.
        val broken = JSONArray()
            .put(JSONObject().put("modality", "TEXT").put("tokenCount", 351))
            .put(JSONObject().put("modality", "AUDIO").put("tokenCount", 221))
        val expected = 351 / 1e6 * 0.75 + 221 / 1e6 * 3.0 + 27 / 1e6 * 0.75
        assertEquals(expected, newSpend(baseline, "prompt", 599, broken, rates), 1e-12)
    }

    @Test
    fun missingOrUnknownDetailsCostNothingExtra() {
        val baseline = HashMap<String, Long>()
        assertEquals(0.0, newSpend(baseline, "prompt", 0, null, rates), 1e-9)
        // An unrecognised modality falls back to the text rate rather than crashing.
        assertEquals(0.75, newSpend(baseline, "prompt", 1_000_000, details("SOMETHING_NEW", 1_000_000), rates), 1e-9)
        // A headline count smaller than its own breakdown must not produce negative spend.
        assertEquals(0.0, newSpend(baseline, "prompt", 1, details("SOMETHING_NEW", 1_000_000), rates), 1e-9)
    }
}
