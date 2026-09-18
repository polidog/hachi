package dev.polidog.hachi

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Settings as master/detail: the list of items on the left, the selected item's controls on the
 * right. The screen is 960x480, so there is room for both at once and no need to dive into a dialog
 * for every change.
 */
class SettingsActivity : Activity() {
    /** One settings row. [detail] fills the right-hand pane when the row is selected. */
    private class Item(
        val title: String,
        val summary: () -> String,
        val detail: (SettingsActivity, LinearLayout) -> Unit,
    )

    private lateinit var settings: Settings
    private lateinit var usage: Usage
    private lateinit var list: LinearLayout
    private lateinit var pane: LinearLayout
    private lateinit var items: List<Pair<String?, Item>> // (section header, item)
    private val main = Handler(Looper.getMainLooper())
    private var selected = 0

    /** Filled in the background from ListModels; falls back until it lands. */
    @Volatile private var models: List<String> = ModelCatalog.FALLBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        usage = Usage(this)
        items = buildItems()
        // Opening a pane that happens to contain a text field must not throw the keyboard up over
        // half the screen; the field is focused when it is tapped, not before.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            // Takes the initial focus itself so an EditText inside it does not grab it on open.
            isFocusableInTouchMode = true
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(INK)
                addView(
                    ScrollView(context).apply {
                        addView(list)
                        setBackgroundColor(SURFACE)
                    },
                    LinearLayout.LayoutParams(0, -1, 0.36f),
                )
                addView(
                    ScrollView(context).apply { addView(pane) },
                    LinearLayout.LayoutParams(0, -1, 0.64f),
                )
            }
        )

        drawList()
        select(0)

        thread {
            val fetched = ModelCatalog.fetch(settings.geminiKey)
            main.post {
                models = fetched
                // The model pane may be open and still showing the fallback list.
                if (items[selected].second.title == getString(R.string.settings_model)) select(selected)
            }
        }
    }

    private fun buildItems(): List<Pair<String?, Item>> = listOf(
        getString(R.string.settings_section_conversation) to Item(
            title = getString(R.string.settings_api_key),
            summary = {
                val key = settings.geminiKey
                // Only the tail is shown: enough to tell two keys apart without putting one on screen.
                if (key.isBlank()) getString(R.string.settings_unset) else "••••" + key.takeLast(4)
            },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_api_key),
                    getString(R.string.settings_api_key_help),
                    host.settings.geminiKey,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                ) { host.settings.setSecret("geminiKey", it.trim()) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_name),
            summary = { settings.assistantName },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_name),
                    getString(R.string.settings_name_help),
                    host.settings.assistantName,
                    InputType.TYPE_CLASS_TEXT,
                ) { host.settings.set("assistantName", it.trim()) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_user_name),
            summary = { settings.userName.ifBlank { getString(R.string.settings_unset) } },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_user_name),
                    getString(R.string.settings_user_name_help),
                    host.settings.userName,
                    InputType.TYPE_CLASS_TEXT,
                ) { host.settings.set("userName", it.trim()) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_wake),
            summary = { getString(if (settings.wakeEnabled) R.string.settings_on else R.string.settings_off) },
            detail = { host, pane ->
                val on = getString(R.string.settings_on)
                val off = getString(R.string.settings_off)
                host.choicePane(
                    pane,
                    getString(R.string.settings_wake),
                    getString(R.string.settings_wake_help),
                    listOf(on, off),
                    if (host.settings.wakeEnabled) on else off,
                ) { host.settings.wakeEnabled = it == on }
            },
        ),
        null to Item(
            title = getString(R.string.settings_model),
            summary = { settings.model },
            detail = { host, pane ->
                host.choicePane(
                    pane,
                    getString(R.string.settings_model),
                    getString(R.string.settings_model_help),
                    host.models,
                    host.settings.model,
                ) { host.settings.set("model", it) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_voice),
            summary = { settings.voice },
            detail = { host, pane ->
                host.choicePane(
                    pane,
                    getString(R.string.settings_voice),
                    getString(R.string.settings_voice_help),
                    VOICES,
                    host.settings.voice,
                ) { host.settings.set("voice", it) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_silence),
            summary = { getString(R.string.settings_seconds, settings.silenceTimeout) },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_silence),
                    getString(R.string.settings_silence_help),
                    host.settings.silenceTimeout.toString(),
                    InputType.TYPE_CLASS_NUMBER,
                ) { host.settings.set("silenceTimeout", it.trim().ifBlank { "30" }) }
            },
        ),
        getString(R.string.settings_section_weather) to Item(
            title = getString(R.string.settings_place),
            summary = { settings.weatherPlace?.name ?: getString(R.string.settings_unset) },
            detail = { host, pane -> host.placePane(pane) },
        ),
        null to Item(
            title = getString(R.string.settings_yahoo_appid),
            summary = {
                val id = settings.yahooAppId
                if (id.isBlank()) getString(R.string.settings_unset) else "••••" + id.takeLast(4)
            },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_yahoo_appid),
                    getString(R.string.settings_yahoo_appid_help),
                    host.settings.yahooAppId,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                ) { host.settings.setSecret("yahooAppId", it.trim()) }
            },
        ),
        getString(R.string.settings_section_house) to Item(
            title = getString(R.string.settings_ha_url),
            summary = { settings.homeAssistantUrl.ifBlank { getString(R.string.settings_unset) } },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_ha_url),
                    getString(R.string.settings_ha_url_help),
                    host.settings.homeAssistantUrl,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                ) { host.settings.set("homeAssistantUrl", it.trim()) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_ha_token),
            summary = {
                val token = settings.homeAssistantToken
                if (token.isBlank()) getString(R.string.settings_unset) else "\u2022\u2022\u2022\u2022" + token.takeLast(4)
            },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_ha_token),
                    getString(R.string.settings_ha_token_help),
                    host.settings.homeAssistantToken,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                ) { host.settings.setSecret("homeAssistantToken", it.trim()) }
            },
        ),
        getString(R.string.settings_section_spend) to Item(
            title = getString(R.string.settings_daily_cap),
            summary = {
                val cap = settings.dailyCapUsd
                if (cap <= 0.0) getString(R.string.settings_no_limit) else "$" + String.format("%.2f", cap)
            },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_daily_cap),
                    getString(R.string.settings_daily_cap_help),
                    host.settings.get("dailyCap", Settings.DEFAULT_DAILY_CAP),
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
                ) { host.settings.set("dailyCap", it.trim().ifBlank { "0" }) }
            },
        ),
        null to Item(
            title = getString(R.string.settings_usage),
            summary = { usage.label() },
            detail = { host, pane ->
                pane.addView(host.paneTitle(host.getString(R.string.settings_usage)))
                pane.addView(host.paneHelp(host.getString(R.string.settings_usage_help)))
                pane.addView(
                    TextView(host).apply {
                        text = host.usage.label()
                        textSize = 26f
                        setTextColor(TEXT)
                        setPadding(0, host.dp(12), 0, 0)
                    }
                )
            },
        ),
    )

    private fun drawList() {
        list.removeAllViews()
        list.addView(
            TextView(this).apply {
                text = getString(R.string.settings_title)
                textSize = 19f
                setTextColor(TEXT)
                setPadding(dp(18), dp(16), dp(18), dp(6))
            }
        )
        items.forEachIndexed { index, (section, item) ->
            if (section != null) {
                list.addView(
                    TextView(this).apply {
                        text = section
                        textSize = 12f
                        setTextColor(ACCENT_INK)
                        setPadding(dp(18), dp(12), dp(18), dp(2))
                    }
                )
            }
            list.addView(row(index, item))
        }
    }

    private fun row(index: Int, item: Item) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(9), dp(14), dp(9))
        if (index == selected) setBackgroundColor(SURFACE_ON)
        addView(
            TextView(context).apply {
                text = item.title
                textSize = 15f
                setTextColor(if (index == selected) ACCENT_INK else TEXT)
            }
        )
        addView(
            TextView(context).apply {
                text = item.summary()
                textSize = 12f
                setTextColor(MUTED)
            }
        )
        setOnClickListener { select(index) }
    }

    private fun select(index: Int) {
        selected = index
        drawList()
        pane.removeAllViews()
        items[index].second.detail(this, pane)
    }

    private fun choicePane(
        pane: LinearLayout,
        title: String,
        help: String,
        options: List<String>,
        current: String,
        save: (String) -> Unit,
    ) {
        pane.addView(paneTitle(title))
        pane.addView(paneHelp(help))
        // A value chosen before the catalog knew about it must still be selectable.
        val all = if (current in options) options else options + current
        pane.addView(
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                all.forEachIndexed { i, option ->
                    addView(
                        RadioButton(context).apply {
                            id = i + 1
                            text = option
                            textSize = 14f
                            setTextColor(TEXT)
                            setPadding(dp(6), dp(7), 0, dp(7))
                        }
                    )
                }
                check(all.indexOf(current) + 1)
                setOnCheckedChangeListener { _, id ->
                    if (id > 0) {
                        save(all[id - 1])
                        drawList() // the summary in the left-hand list just changed
                    }
                }
            }
        )
    }

    private fun textPane(
        pane: LinearLayout,
        title: String,
        help: String,
        current: String,
        inputType: Int,
        save: (String) -> Unit,
    ) {
        pane.addView(paneTitle(title))
        pane.addView(paneHelp(help))
        val field = EditText(this).apply {
            setText(current)
            this.inputType = inputType
            textSize = 15f
            setTextColor(TEXT)
            setSelection(text.length)
        }
        pane.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        pane.addView(
            accentButton(getString(R.string.settings_save)).apply {
                setOnClickListener {
                    save(field.text.toString())
                    drawList()
                    Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); gravity = Gravity.END },
        )
    }

    /** Place search: type a name, pick one of the hits, and its coordinates are what gets stored. */
    private fun placePane(pane: LinearLayout) {
        pane.addView(paneTitle(getString(R.string.settings_place)))
        pane.addView(paneHelp(getString(R.string.settings_place_help)))

        val current = TextView(this).apply {
            text = settings.weatherPlace?.name ?: getString(R.string.settings_unset)
            textSize = 16f
            setTextColor(TEXT)
            setPadding(0, dp(4), 0, dp(10))
        }
        pane.addView(current)

        val field = EditText(this).apply {
            hint = getString(R.string.settings_place_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setTextColor(TEXT)
        }
        pane.addView(field, LinearLayout.LayoutParams(-1, -2))

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(
            accentButton(getString(R.string.settings_search)).apply {
                setOnClickListener {
                    val query = field.text.toString().trim()
                    if (query.isBlank()) return@setOnClickListener
                    results.removeAllViews()
                    results.addView(resultLine(getString(R.string.settings_searching), null))
                    val language = if (java.util.Locale.getDefault().language == "ja") "ja" else "en"
                    thread {
                        val found = Weather.search(query, language)
                        main.post {
                            results.removeAllViews()
                            if (found.isEmpty()) {
                                results.addView(resultLine(getString(R.string.settings_no_results), null))
                            }
                            for (place in found) {
                                results.addView(
                                    resultLine(place.name) {
                                        settings.setWeatherPlace(place)
                                        current.text = place.name
                                        drawList() // the summary in the left-hand list just changed
                                        Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.END },
        )
        pane.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** The one button per pane that does something: the accent, the way the rest of the app uses it. */
    private fun accentButton(title: String) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        setTextColor(ON_ACCENT)
        background = pill(dp(20).toFloat(), ACCENT)
        stateListAnimator = null
        setPadding(dp(22), 0, dp(22), 0)
    }

    private fun resultLine(text: String, onClick: (() -> Unit)?) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(if (onClick == null) MUTED else TEXT)
        setPadding(0, dp(9), 0, dp(9))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun paneTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(TEXT)
    }

    private fun paneHelp(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(6), 0, dp(4))
    }
}
