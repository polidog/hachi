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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Settings grouped into tabs above a master/detail layout: the active category's items on the
 * left and the selected item's controls on the right. The screen is 960x480, so there is room for
 * both at once and no need to dive into a dialog for every change.
 *
 * Set on the same paper as the rest of the wall, with none of the platform's own widgets showing:
 * no underlined fields, no radio buttons, no highlighted slab for the selected row. The selected
 * row is bold with the accent's dot beside it, and a choice is a row with a ring that fills in.
 */
class SettingsActivity : Activity() {
    /** One settings row. [detail] fills the right-hand pane when the row is selected. */
    private class Item(
        val category: Int,
        val title: String,
        val summary: () -> String,
        val detail: (SettingsActivity, LinearLayout) -> Unit,
    )

    private lateinit var settings: Settings
    private lateinit var usage: Usage
    private lateinit var list: LinearLayout
    private lateinit var pane: LinearLayout
    private lateinit var items: List<Item>
    private lateinit var listScroll: ScrollView
    private lateinit var paneScroll: ScrollView
    private val tabs = mutableMapOf<Int, Pair<TextView, View>>()
    private val categorySelections = mutableMapOf<Int, Int>()
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

        list.setPadding(0, dp(12), 0, dp(12))
        listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        paneScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(pane)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
            addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(64)))
            addView(View(context).apply { setBackgroundColor(HAIRLINE) },
                LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(listScroll, LinearLayout.LayoutParams(0, -1, 0.34f))
                addView(View(context).apply { setBackgroundColor(HAIRLINE) },
                    LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(0, dp(20), 0, dp(20)) })
                addView(paneScroll, LinearLayout.LayoutParams(0, -1, 0.66f))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        select(0)

        thread {
            val fetched = ModelCatalog.fetch(settings.geminiKey)
            main.post {
                models = fetched
                // The model pane may be open and still showing the fallback list.
                if (items[selected].title == getString(R.string.settings_model)) select(selected)
            }
        }
    }

    private fun buildItems(): List<Item> = listOf(
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
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
        Item(
            category = R.string.settings_section_conversation,
            title = getString(R.string.settings_dim),
            summary = {
                val after = settings.dimAfter
                if (after == 0L) getString(R.string.settings_dim_never)
                else getString(R.string.settings_minutes, after)
            },
            detail = { host, pane ->
                host.textPane(
                    pane,
                    getString(R.string.settings_dim),
                    getString(R.string.settings_dim_help),
                    host.settings.dimAfter.toString(),
                    InputType.TYPE_CLASS_NUMBER,
                ) { host.settings.set("dimAfter", it.trim().ifBlank { "5" }) }
            },
        ),
        Item(
            category = R.string.settings_section_weather,
            title = getString(R.string.settings_place),
            summary = { settings.weatherPlace?.name ?: getString(R.string.settings_unset) },
            detail = { host, pane -> host.placePane(pane) },
        ),
        Item(
            category = R.string.settings_section_weather,
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
        Item(
            category = R.string.settings_section_calendar,
            title = getString(R.string.settings_calendars),
            summary = {
                val hidden = settings.hiddenCalendars.size
                if (hidden == 0) getString(R.string.settings_calendars_all)
                else getString(R.string.settings_calendars_hidden, hidden)
            },
            detail = { host, pane -> host.calendarPane(pane) },
        ),
        Item(
            category = R.string.settings_section_house,
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
        Item(
            category = R.string.settings_section_house,
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
        Item(
            category = R.string.settings_section_house,
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
        Item(
            category = R.string.settings_section_spend,
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
        Item(
            category = R.string.settings_section_spend,
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

    private fun buildHeader() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = getString(R.string.settings_title)
            textSize = 24f
            typeface = DISPLAY
            setTextColor(TEXT)
            setPadding(dp(28), 0, dp(24), 0)
        }, LinearLayout.LayoutParams(-2, -2))
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                for (category in items.map { it.category }.distinct()) {
                    val label = TextView(context).apply {
                        text = getString(category)
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setPadding(dp(18), 0, dp(18), 0)
                        minWidth = dp(80)
                        isFocusable = true
                        setOnClickListener { selectCategory(category) }
                    }
                    val indicator = View(context).apply { setBackgroundColor(ACCENT) }
                    tabs[category] = label to indicator
                    addView(FrameLayout(context).apply {
                        addView(label, FrameLayout.LayoutParams(-1, -1))
                        addView(indicator, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM)
                            .apply { setMargins(dp(18), 0, dp(18), 0) })
                    }, LinearLayout.LayoutParams(-2, -1))
                }
            }, FrameLayout.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(TextView(context).apply {
            text = "×"
            textSize = 30f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            isFocusable = true
            contentDescription = getString(R.string.settings_close)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), -1).apply { marginEnd = dp(8) })
    }

    private fun selectCategory(category: Int) {
        if (items[selected].category == category) return
        select(categorySelections[category] ?: items.indexOfFirst { it.category == category })
        listScroll.scrollTo(0, 0)
        // Bring the remembered row back into view even in the longer conversation menu.
        list.post {
            val row = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isSelected }
            if (row != null) listScroll.smoothScrollTo(0, row.top)
        }
    }

    private fun drawList() {
        list.removeAllViews()
        items.forEachIndexed { index, item ->
            if (item.category == items[selected].category) list.addView(row(index, item))
        }
    }

    private fun row(index: Int, item: Item) = LinearLayout(this).apply {
        val on = index == selected
        isSelected = on
        isFocusable = true
        minimumHeight = dp(56)
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
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(pane.windowToken, 0)
        selected = index
        categorySelections[items[index].category] = index
        for ((category, views) in tabs) {
            val (label, indicator) = views
            val on = category == items[index].category
            label.isSelected = on
            label.typeface = if (on) DISPLAY else null
            label.setTextColor(if (on) TEXT else MUTED)
            indicator.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        drawList()
        pane.removeAllViews()
        items[index].detail(this, pane)
        pane.requestFocus()
        paneScroll.scrollTo(0, 0)
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
