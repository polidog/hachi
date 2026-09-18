package dev.polidog.hachi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import dev.polidog.hachi.tools.refreshHouseTools
import kotlin.concurrent.thread

/** The clock and the weather. Everything else lifts over them from a button. */
private const val PAGES = 2

class MainActivity : Activity(), Conversation.Ui {
    private lateinit var settings: Settings
    private lateinit var captions: ConversationView
    private lateinit var spend: TextView
    private lateinit var talk: View
    private lateinit var devices: View
    private lateinit var agenda: View
    /** Set while a conversation is running: the buttons are gone for its whole length. */
    private var talking = false
    private lateinit var dots: TextView
    private lateinit var weatherPage: WeatherPage
    private lateinit var housePage: HousePage
    private lateinit var calendarPage: CalendarPage
    private lateinit var pager: PagerView
    private lateinit var house: House
    private lateinit var sky: SkyView
    private lateinit var weather: WeatherStore
    private var conversation: Conversation? = null
    private val wake by lazy { WakeWord(this, settings) { startConversation(calledByName = true) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        weather = WeatherStore(this, settings)
        captions = ConversationView(this).apply {
            onHush = { conversation?.hush() }
            onEnd = { conversation?.stop() }
        }
        talk = talkButton()
        devices = devicesButton()
        agenda = agendaButton()
        spend = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 11f
            text = Usage(this@MainActivity).label()
        }

        weatherPage = WeatherPage(this)
        house = House(settings)
        housePage = HousePage(this, house)
        house.onUpdate = { housePage.bind() }
        house.onError = { Toast.makeText(this, R.string.house_action_failed, Toast.LENGTH_LONG).show() }
        housePage.bind()
        calendarPage = CalendarPage(this)
        sky = SkyView(this)
        dots = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 10f
        }
        pager = PagerView(this).apply {
            addPage(ClockView(context))
            addPage(weatherPage)
            // The weather emblem belongs to the clock page, so it leaves with it.
            onScrolled = { sky.emblemOffset = it.toFloat() }
            onPageChanged = { page ->
                showDots(page)
                bindButtons()
                // The calendar belongs to the clock; swiping away from it puts the calendar away.
                if (calendarPage.showing) calendarPage.hide()
            }
        }
        showDots(0)

        setContentView(
            FrameLayout(this).apply {
                addView(sky)
                addView(pager)
                // Both lift over the pager and sit under the floating buttons, which stay live.
                addView(housePage)
                addView(calendarPage)
                addView(
                    dots,
                    FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                        .apply { topMargin = dp(10) },
                )
                addView(
                    // Top right, beside the settings button: the top left is the pages' own, and
                    // the bottom corners belong to the buttons. The era moved onto the clock.
                    spend,
                    FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
                        .apply { rightMargin = dp(66); topMargin = dp(24) },
                )
                addView(
                    settingsButton(),
                    FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END)
                        .apply { rightMargin = dp(16); topMargin = dp(16) },
                )
                addView(
                    // Bottom left corner, out of the way of the pages' own middles.
                    talk,
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START)
                        .apply { leftMargin = dp(16); bottomMargin = dp(16) },
                )
                addView(
                    devices,
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START)
                        .apply { leftMargin = dp(84); bottomMargin = dp(16) },
                )
                addView(
                    agenda,
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START)
                        .apply { leftMargin = dp(152); bottomMargin = dp(16) },
                )
                addView(captions)
            }
        )
    }

    /** Debug builds only: `-e name debugScene -e value THUNDER` pins the sky to one scene. */
    private fun debugScene(): SkyScene? {
        if (!BuildConfig.DEBUG) return null
        val name = settings.get("debugScene").trim()
        return SkyScene.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Page indicator: the page you are on is the one wearing the accent. */
    private fun showDots(current: Int) {
        val text = SpannableString((0 until PAGES).joinToString("  ") { "●" })
        val at = current * 3
        text.setSpan(ForegroundColorSpan(ACCENT_INK), at, at + 1, 0)
        text.setSpan(ForegroundColorSpan(MUTED), 0, at, 0)
        text.setSpan(ForegroundColorSpan(MUTED), at + 1, text.length, 0)
        dots.text = text
    }

    private fun settingsButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_settings)
        val pad = dp(9)
        setPadding(pad, pad, pad, pad)
        imageAlpha = 0xB3
        background = card(radius = 21)
        contentDescription = getString(R.string.settings_title)
        setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
    }

    private fun talkButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_mic)
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
        imageTintList = ColorStateList.valueOf(ON_ACCENT)
        // The one thing on the screen wearing the accent: the button worth pressing.
        background = pill(dp(28).toFloat(), fill = ACCENT)
        contentDescription = getString(R.string.action_talk)
        setOnClickListener { startConversation() }
    }

    private fun agendaButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_calendar)
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
        imageAlpha = 0xE6
        background = card(radius = 28)
        contentDescription = getString(R.string.calendar_title)
        setOnClickListener { if (calendarPage.showing) calendarPage.hide() else calendarPage.show() }
    }

    private fun devicesButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_devices)
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
        imageAlpha = 0xE6
        background = card(radius = 28)
        contentDescription = getString(R.string.action_devices)
        setOnClickListener { if (housePage.showing) hideHouse() else showHouse() }
    }

    /** The house is asked how it is doing as it opens, not while nobody is looking at it. */
    private fun showHouse() {
        housePage.show()
        refreshHouse()
        dots.visibility = View.GONE
        bindButtons()
        if (calendarPage.showing) calendarPage.hide()
    }

    /**
     * The floating buttons belong to the clock.
     *
     * Every other page is a full screen of its own, and three buttons sitting over the bottom left
     * of it are three buttons in the way of what you came to that page to read.
     */
    private fun bindButtons() {
        val on = pager.page == 0 && !talking
        talk.visibility = if (on) View.VISIBLE else View.GONE
        devices.visibility = if (on) View.VISIBLE else View.GONE
        // The calendar would lift over the house it was opened from.
        agenda.visibility = if (on && !housePage.showing) View.VISIBLE else View.GONE
    }

    private fun startConversation(calledByName: Boolean = false) {
        if (conversation?.active == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // The calendar rides along with the microphone: both are asked for while someone is
            // standing at the screen, and the conversation only waits on the microphone.
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR),
                1,
            )
            return
        }
        // Both want the one microphone, and Julius holds it until it is told not to.
        wake.stop()
        conversation = Conversation(this, settings, this).also { it.start(greet = calledByName) }
    }

    /**
     * Listens for the name, if that is switched on and there is a microphone to listen with.
     *
     * Permission is never asked for here: being woken by name is not worth a dialog on a screen
     * nobody is standing at. The talk button asks, and from then on this works too.
     */
    private fun listenForName() {
        val name = settings.assistantName
        val on = settings.wakeEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (on) wake.start(name) else wake.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startConversation()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed the key or the cap, and the spend line is stale after a session.
        spend.text = Usage(this).label()
        // Settings may also have renamed Hachi or switched being called by name on or off.
        listenForName()
        // What the house can do is asked for here and used by the next conversation, not this one:
        // starting a session cannot wait for the network.
        thread {
            refreshHouseTools(settings)
            house.refresh()
        }
        // Also picks up a place that was just chosen in Settings.
        weather.start {
            weatherPage.bind(it)
            sky.bind(it.forecast, debugScene())
        }
    }

    override fun onPause() {
        super.onPause()
        // A conversation cut short by the screen going elsewhere looks exactly like one the server
        // dropped, unless this says which it was.
        if (conversation?.active == true) android.util.Log.i("Hachi", "paused while talking")
        conversation?.stop()
        wake.stop()
        weather.stop()
    }

    override fun onState(state: Conversation.State) {
        when (state) {
            Conversation.State.CONNECTING -> {
                captions.show(getString(R.string.state_connecting))
                talking = true
                bindButtons()
                // What was said is what the screen is for while talking; these would sit on top.
                if (calendarPage.showing) calendarPage.hide()
                if (housePage.showing) hideHouse()
            }
            // Nothing: the orb is what says it is listening, and a black screen should hold only
            // what was actually said.
            Conversation.State.LISTENING -> captions.setStatus("")
            Conversation.State.ENDED -> {
                captions.hide()
                talking = false
                bindButtons()
                spend.text = Usage(this).label()
                // The microphone is free again, so go back to waiting for the name.
                listenForName()
            }
        }
    }

    override fun onSpend(label: String) { spend.text = label }

    /**
     * Any tool at all, not just the house's: the house names its own tools, so telling them apart by
     * name is a guess that goes stale. One extra call over the LAN after the weather is cheaper than
     * a page that quietly stops following the conversation.
     */
    override fun onToolUsed(name: String) = refreshHouse()

    @Deprecated("Uses the platform back callback on this API 30 device")
    override fun onBackPressed() {
        if (calendarPage.showing) {
            calendarPage.back()
            return
        }
        if (housePage.showing) {
            // Back steps out of a device and then out of a room before it puts the house away.
            if (!housePage.back()) hideHouse()
            return
        }
        super.onBackPressed()
    }

    private fun hideHouse() {
        housePage.hide()
        dots.visibility = View.VISIBLE
        bindButtons()
    }

    private fun refreshHouse() {
        thread { house.refresh() }
    }

    override fun onUserText(text: String) = captions.appendUser(text)

    override fun onAssistantText(text: String) = captions.appendAssistant(text)

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
