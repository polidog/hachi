package dev.polidog.hachi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/**
 * Running spend, accumulated from the `usageMetadata` the Live API reports during a session.
 *
 * Only the dollar figure is kept, per calendar month, so switching models mid-month still adds up
 * correctly (each chunk of tokens is priced when it arrives, at the rate of the model that produced
 * it). Nothing here talks to Cloud Billing -- that data lags by hours, and the token counts the
 * session already hands us are exact.
 */
class Usage(context: Context) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    /** Last cumulative count seen this session, per side and modality. */
    private val baseline = HashMap<String, Long>()

    fun observe(model: String, metadata: JSONObject) {
        val rates = Rates.forModel(model)
        val spend = newSpend(baseline, "prompt", metadata.optJSONArray("promptTokensDetails"), rates.input) +
            newSpend(baseline, "response", metadata.optJSONArray("responseTokensDetails"), rates.output)
        if (spend > 0.0) prefs.edit().putString(monthKey(), (monthUsd + spend).toString()).apply()
    }

    /** Forgets the per-session baseline, so the next session's counts start from zero again. */
    fun endSession() = baseline.clear()

    val monthUsd: Double
        get() = prefs.getString(monthKey(), "0")?.toDoubleOrNull() ?: 0.0

    /** e.g. "$0.042" -- small numbers need the extra digit to show movement at all. */
    fun monthLabel(): String = "$" + String.format("%.3f", monthUsd)

    private fun monthKey() = "usd." + YearMonth.now()

    /** USD per 1M tokens. Source: ai.google.dev/gemini-api/docs/pricing, checked 2026-09-17. */
    private class Rates(val input: Map<String, Double>, val output: Map<String, Double>) {
        companion object {
            private val LIVE_3_8 = Rates(
                input = mapOf("TEXT" to 0.75, "AUDIO" to 3.00, "IMAGE" to 1.00, "VIDEO" to 1.00),
                output = mapOf("TEXT" to 4.50, "AUDIO" to 12.00, "IMAGE" to 0.0, "VIDEO" to 0.0),
            )

            /** Every Live model is priced from this table for now; revisit when a second one is used. */
            fun forModel(model: String): Rates = LIVE_3_8
        }
    }
}

/**
 * Prices the tokens that are new since the last report, updating [baseline] in place.
 *
 * Usage is reported as a session running total, so only the growth is new spend. A count that went
 * backwards means the session restarted, so it is taken at face value.
 * ponytail: verify against the logged usageMetadata on the device -- if the API turns out to report
 * per-message deltas instead, drop the subtraction and price the raw counts.
 */
internal fun newSpend(
    baseline: MutableMap<String, Long>,
    side: String,
    details: JSONArray?,
    rates: Map<String, Double>,
): Double {
    if (details == null) return 0.0
    var usd = 0.0
    for (i in 0 until details.length()) {
        val entry = details.optJSONObject(i) ?: continue
        val modality = entry.optString("modality", "TEXT").ifEmpty { "TEXT" }
        val count = entry.optLong("tokenCount")
        val key = "$side:$modality"
        val previous = baseline[key] ?: 0L
        val added = if (count >= previous) count - previous else count
        baseline[key] = count
        usd += added / 1_000_000.0 * (rates[modality] ?: rates.getValue("TEXT"))
    }
    return usd
}
