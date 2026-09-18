package dev.polidog.hachi

import android.app.Activity
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Settings as master/detail: the list of items on the left, the selected item's controls on the
 * right. The screen is 960x480, so there is room for both at once and no need to dive into a dialog
 * for every change.
 *
 * Set on the same paper as the rest of the wall, with none of the platform's own widgets showing:
 * no underlined fields, no radio buttons, no highlighted slab for the selected row. The selected
 * row is bold with the accent's dot beside it, and a choice is a row with a ring that fills in.
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
        // Built in whichever palette the hour calls for, like the wall behind it.
        Theme.refresh()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(INK))
        settings = Settings(this)
        usage = Usage(this)
        items = buildItems()
        // Opening a pane that happens to contain a text field must not throw the keyboard up over
        // half the screen; the field is focused when it is tapped, not before.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        // Full screen like the wall itself; the × in the corner is the way out.
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(28), dp(64), dp(28))
            // Takes the initial focus itself so an EditText inside it does not grab it on open.
            isFocusableInTouchMode = true
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(INK)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        ScrollView(context).apply {
                            isVerticalScrollBarEnabled = false
                            addView(list)
                        },
                        LinearLayout.LayoutParams(0, -1, 0.34f),
                    )
                    // A hairline, not a change of paper, is all that divides the two panes.
                    addView(View(context).apply { setBackgroundColor(HAIRLINE) },
                        LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(0, dp(28), 0, dp(28)) })
                    addView(
                        ScrollView(context).apply {
                            isVerticalScrollBarEnabled = false
                            addView(pane)
                        },
                        LinearLayout.LayoutParams(0, -1, 0.66f),
                    )
                })
                addView(
                    TextView(context).apply {
                        text = "×"
                        textSize = 30f
                        setTextColor(TEXT)
                        gravity = Gravity.CENTER
                        contentDescription = getString(R.string.settings_close)
                        setOnClickListener { finish() }
                    },
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END)
                        .apply { setMargins(0, dp(10), dp(10), 0) },
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
        getString(R.string.settings_section_calendar) to Item(
            title = getString(R.string.settings_calendars),
            summary = {
                val hidden = settings.hiddenCalendars.size
                if (hidden == 0) getString(R.string.settings_calendars_all)
                else getString(R.string.settings_calendars_hidden, hidden)
            },
            detail = { host, pane -> host.calendarPane(pane) },
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
        null to Item(
            title = getString(R.string.settings_typesafe_key),
            summary = {
                val key = settings.typesafeKey
                if (key.isBlank()) getString(R.string.settings_unset) else "\u2022\u2022\u2022\u2022" + key.takeLast(4)
            },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_typesafe_key),
                    getString(R.string.settings_typesafe_key_help),
                    host.settings.typesafeKey,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                ) { host.settings.setSecret("typesafeKey", it.trim()) }
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
                        textSize = 36f
                        typeface = DISPLAY
                        setTextColor(TEXT)
                        setPadding(0, host.dp(16), 0, 0)
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
                textSize = 28f
                typeface = DISPLAY
                setTextColor(TEXT)
                setPadding(dp(32), dp(24), dp(18), dp(8))
            }
        )
        items.forEachIndexed { index, (section, item) ->
            if (section != null) {
                list.addView(
                    TextView(this).apply {
                        text = section
                        textSize = 11f
                        letterSpacing = 0.25f
                        isAllCaps = true
                        setTextColor(MUTED)
                        setPadding(dp(32), dp(20), dp(18), dp(4))
                    }
                )
            }
            list.addView(row(index, item))
        }
    }

    private fun row(index: Int, item: Item) = LinearLayout(this).apply {
        val on = index == selected
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(14), dp(8))
        // The dot is always laid out, only painted when selected, so nothing shifts as it moves.
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            visibility = if (on) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = item.title
                    textSize = 15f
                    typeface = if (on) DISPLAY else null
                    setTextColor(TEXT)
                }
            )
            addView(
                TextView(context).apply {
                    text = item.summary()
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(MUTED)
                }
            )
        })
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
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun draw(chosen: String) {
            rows.removeAllViews()
            for (option in all) {
                val on = option == chosen
                rows.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(9), 0, dp(9))
                    addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            if (on) setColor(ACCENT) else setStroke(dp(2), HAIRLINE_STRONG)
                        }
                    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                    addView(TextView(context).apply {
                        text = option
                        textSize = 15f
                        typeface = if (on) DISPLAY else null
                        setTextColor(TEXT)
                    })
                    isSelected = on
                    contentDescription = option
                    setOnClickListener {
                        if (on) return@setOnClickListener
                        save(option)
                        draw(option)
                        drawList() // the summary in the left-hand list just changed
                    }
                })
            }
        }
        draw(current)
        pane.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
        val field = field().apply {
            setText(current)
            this.inputType = inputType
            setSelection(text.length)
        }
        pane.addView(field, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        pane.addView(
            accentButton(getString(R.string.settings_save)).apply {
                setOnClickListener {
                    save(field.text.toString())
                    drawList()
                    Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(14); gravity = Gravity.END },
        )
    }

    /** One row per synced calendar; tapping flips whether it is shown. */
    private fun calendarPane(pane: LinearLayout) {
        pane.addView(paneTitle(getString(R.string.settings_calendars)))
        pane.addView(paneHelp(getString(R.string.settings_calendars_help)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (!calendarPermitted(this)) {
            rows.addView(resultLine(getString(R.string.calendar_no_permission), null))
            return
        }
        thread {
            val calendars = listCalendars(this)
            main.post {
                fun draw() {
                    rows.removeAllViews()
                    val hidden = settings.hiddenCalendars
                    for ((id, name) in calendars) {
                        val on = id !in hidden
                        rows.addView(LinearLayout(this).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(9), 0, dp(9))
                            addView(View(context).apply {
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(4).toFloat()
                                    if (on) setColor(ACCENT) else setStroke(dp(2), HAIRLINE_STRONG)
                                }
                            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                            addView(TextView(context).apply {
                                text = name
                                textSize = 15f
                                typeface = if (on) DISPLAY else null
                                setTextColor(if (on) TEXT else MUTED)
                            })
                            isSelected = on
                            contentDescription = name
                            setOnClickListener {
                                settings.hiddenCalendars = if (on) hidden + id else hidden - id
                                draw()
                                drawList() // the summary in the left-hand list just changed
                            }
                        })
                    }
                    if (calendars.isEmpty()) rows.addView(resultLine(getString(R.string.calendar_none), null))
                }
                draw()
            }
        }
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

        val field = field().apply {
            hint = getString(R.string.settings_place_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        pane.addView(field, LinearLayout.LayoutParams(-1, dp(52)))

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
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(12); gravity = Gravity.END },
        )
        pane.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** The one button per pane that does something: the accent, the way the rest of the app uses it. */
    private fun accentButton(title: String) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        typeface = DISPLAY
        setTextColor(ON_ACCENT)
        background = pill(dp(24).toFloat(), ACCENT)
        stateListAnimator = null
        minWidth = dp(120)
        setPadding(dp(28), 0, dp(28), 0)
    }

    /** A text field as a square well in the paper: the platform's underline is the one line too many. */
    private fun field() = EditText(this).apply {
        textSize = 16f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        background = pill(0f, SURFACE)
        setPadding(dp(18), 0, dp(18), 0)
        isSingleLine = true
    }

    private fun resultLine(text: String, onClick: (() -> Unit)?) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(if (onClick == null) MUTED else TEXT)
        setPadding(0, dp(11), 0, dp(11))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun paneTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 30f
        typeface = DISPLAY
        setTextColor(TEXT)
    }

    private fun paneHelp(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(8), 0, dp(4))
    }
}
