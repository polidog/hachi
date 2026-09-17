package dev.polidog.hachi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Settings, laid out like the Android Settings app: category headers over tappable rows. */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var usage: Usage
    private lateinit var rows: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    /** Filled in the background from ListModels; falls back until it lands. */
    @Volatile private var models: List<String> = ModelCatalog.FALLBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        usage = Usage(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(rows) })
        build()
        thread {
            val fetched = ModelCatalog.fetch(settings.geminiKey)
            main.post { models = fetched }
        }
    }

    private fun build() {
        rows.removeAllViews()
        rows.addView(title(getString(R.string.settings_title)))

        rows.addView(header(getString(R.string.settings_section_conversation)))
        rows.addView(
            row(getString(R.string.settings_api_key), maskedKey()) {
                edit(R.string.settings_api_key, settings.geminiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                    settings.setSecret("geminiKey", it.trim())
                }
            }
        )
        rows.addView(
            row(getString(R.string.settings_model), settings.model) {
                choose(R.string.settings_model, models, settings.model) { settings.set("model", it) }
            }
        )
        rows.addView(
            row(getString(R.string.settings_voice), settings.voice) {
                choose(R.string.settings_voice, VOICES, settings.voice) { settings.set("voice", it) }
            }
        )
        rows.addView(
            row(getString(R.string.settings_silence), getString(R.string.settings_seconds, settings.silenceTimeout)) {
                edit(R.string.settings_silence, settings.silenceTimeout.toString(), InputType.TYPE_CLASS_NUMBER) {
                    settings.set("silenceTimeout", it.trim().ifBlank { "30" })
                }
            }
        )

        rows.addView(header(getString(R.string.settings_section_spend)))
        rows.addView(
            row(getString(R.string.settings_daily_cap), capSummary()) {
                edit(R.string.settings_daily_cap, settings.get("dailyCap", Settings.DEFAULT_DAILY_CAP), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) {
                    settings.set("dailyCap", it.trim().ifBlank { "0" })
                }
            }
        )
        rows.addView(row(getString(R.string.settings_usage), usage.label(), null))
    }

    private fun maskedKey(): String {
        val key = settings.geminiKey
        // Only the tail is shown, enough to tell two keys apart without putting one on screen.
        return if (key.isBlank()) getString(R.string.settings_unset) else "••••" + key.takeLast(4)
    }

    private fun capSummary(): String {
        val cap = settings.dailyCapUsd
        return if (cap <= 0.0) getString(R.string.settings_no_limit) else "$" + String.format("%.2f", cap)
    }

    private fun choose(titleRes: Int, options: List<String>, current: String, save: (String) -> Unit) {
        // A value set before the catalog knew about it must still be selectable.
        val items = (if (current in options) options else options + current).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(items, items.indexOf(current)) { dialog, which ->
                save(items[which])
                dialog.dismiss()
                build()
            }
            .show()
    }

    private fun edit(titleRes: Int, current: String, inputType: Int, save: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(current)
            this.inputType = inputType
            setSelection(text.length)
        }
        val pad = (density * 16).toInt()
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(LinearLayout(this).apply { setPadding(pad, pad, pad, 0); addView(field) })
            .setPositiveButton(R.string.settings_save) { _, _ ->
                save(field.text.toString())
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding((density * 16).toInt(), (density * 16).toInt(), 0, (density * 4).toInt())
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(0x8A, 0xB4, 0xF8))
        setPadding((density * 16).toInt(), (density * 14).toInt(), 0, (density * 2).toInt())
    }

    private fun row(label: String, summary: String, onClick: (() -> Unit)?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (density * 16).toInt()
        val gap = (density * 10).toInt()
        setPadding(pad, gap, pad, gap)
        addView(TextView(context).apply { text = label; textSize = 16f })
        addView(
            TextView(context).apply {
                text = summary
                textSize = 13f
                setTextColor(Color.argb(0x99, 0xFF, 0xFF, 0xFF))
            }
        )
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
            // Keeps the rows feeling like buttons rather than static text.
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        } else {
            alpha = 0.85f
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
}
