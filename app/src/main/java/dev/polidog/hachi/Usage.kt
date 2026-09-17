package dev.polidog.hachi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
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
        // thoughtsTokenCount arrives outside both responseTokenCount and totalTokenCount, and Gemini
        // bills thinking as output, so it is added to the output side by hand. If it turns out not to
        // be billed, this over-reports -- which is the safer direction for a number the user budgets
        // against.
        val spend = newSpend(
            baseline,
            "prompt",
            metadata.optLong("promptTokenCount"),
            metadata.optJSONArray("promptTokensDetails"),
            rates.input,
        ) + newSpend(
            baseline,
            "response",
            metadata.optLong("responseTokenCount") + metadata.optLong("thoughtsTokenCount"),
            metadata.optJSONArray("responseTokensDetails"),
            rates.output,
        )
        if (spend > 0.0) {
            add(monthKey(), spend)
            add(dayKey(), spend)
        }
    }

    /** Forgets the per-session baseline, so the next session's counts start from zero again. */
    fun endSession() = baseline.clear()

    val monthUsd: Double get() = read(monthKey())

    val todayUsd: Double get() = read(dayKey())

    /** True once today's spend has reached [cap]; a cap of 0 means no limit. */
    fun overDailyCap(cap: Double): Boolean = cap > 0.0 && todayUsd >= cap

    /** e.g. "$0.042 today / $1.230" -- small numbers need the extra digit to show movement at all. */
    fun label(): String = usd(todayUsd) + " today / " + usd(monthUsd)

    private fun usd(value: Double) = "$" + String.format("%.3f", value)

    private fun read(key: String) = prefs.getString(key, "0")?.toDoubleOrNull() ?: 0.0

    private fun add(key: String, spend: Double) {
        prefs.edit().putString(key, (read(key) + spend).toString()).apply()
    }

    private fun monthKey() = "usd." + YearMonth.now()

    // ponytail: one key per day, never pruned. A decade of them is ~100 KB; prune if that ever matters.
    private fun dayKey() = "usd." + LocalDate.now()

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
 *
 * [details] never quite adds up to [total] (one real report broke out 572 of 599 prompt tokens), so
 * whatever is left over is priced as text rather than going unbilled.
 */
internal fun newSpend(
    baseline: MutableMap<String, Long>,
    side: String,
    total: Long,
    details: JSONArray?,
    rates: Map<String, Double>,
): Double {
    var usd = 0.0
    var described = 0L
    if (details != null) {
        for (i in 0 until details.length()) {
            val entry = details.optJSONObject(i) ?: continue
            val modality = entry.optString("modality", "TEXT").ifEmpty { "TEXT" }
            val count = entry.optLong("tokenCount")
            described += count
            usd += charge(baseline, "$side:$modality", count, rates[modality] ?: rates.getValue("TEXT"))
        }
    }
    usd += charge(baseline, "$side:OTHER", (total - described).coerceAtLeast(0L), rates.getValue("TEXT"))
    return usd
}

private fun charge(baseline: MutableMap<String, Long>, key: String, count: Long, rate: Double): Double {
    val previous = baseline[key] ?: 0L
    val added = if (count >= previous) count - previous else count
    baseline[key] = count
    return added / 1_000_000.0 * rate
}
