package dev.polidog.hachi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private const val PAGES = 3

class MainActivity : Activity(), Conversation.Ui {
    private lateinit var settings: Settings
    private lateinit var captions: ConversationView
    private lateinit var spend: TextView
    private lateinit var talk: View
    private lateinit var dots: TextView
    private lateinit var weatherPage: WeatherPage
    private lateinit var radarPage: RadarPage
    private lateinit var sky: SkyView
    private lateinit var weather: WeatherStore
    private var conversation: Conversation? = null

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
        spend = TextView(this).apply {
            setTextColor(Color.argb(0x8A, 0xFA, 0xF6, 0xEC))
            textSize = 11f
            text = Usage(this@MainActivity).label()
        }

        weatherPage = WeatherPage(this)
        radarPage = RadarPage(this, settings)
        sky = SkyView(this)
        dots = TextView(this).apply {
            setTextColor(Color.argb(0x8A, 0xFA, 0xF6, 0xEC))
            textSize = 10f
        }
        val pager = PagerView(this).apply {
            addPage(ClockView(context))
            addPage(weatherPage)
            addPage(radarPage)
            onPageChanged = { showDots(it) }
        }
        showDots(0)

        setContentView(
            FrameLayout(this).apply {
                addView(sky)
                addView(pager)
                addView(
                    // The radar page is a pale map; without this the dots, the spend and the
                    // settings button sit white-on-white.
                    View(context).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.argb(0x6E, 0x06, 0x0A, 0x14), Color.TRANSPARENT),
                        )
                    },
                    FrameLayout.LayoutParams(FILL, dp(64), Gravity.TOP),
                )
                addView(
                    dots,
                    FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                        .apply { topMargin = dp(10) },
                )
                addView(
                    // Top right, beside the settings button: the bottom corners belong to the pages.
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
                    talk,
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        .apply { bottomMargin = dp(14) },
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

    /** Page indicator, filled for the current page. */
    private fun showDots(current: Int) {
        dots.text = (0 until PAGES).joinToString(" ") { if (it == current) "●" else "○" }
    }

    private fun settingsButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_settings)
        val pad = dp(9)
        setPadding(pad, pad, pad, pad)
        imageAlpha = 0xB3
        background = pill(dp(21).toFloat(), strokeWidthPx = dp(1))
        contentDescription = getString(R.string.settings_title)
        setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
    }

    private fun talkButton() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(20)
        val v = dp(11)
        setPadding(h, v, h, v)
        // Opaque enough to read over the radar's map, not just over the sky.
        background = pill(dp(24).toFloat(), fill = Color.argb(0xB8, 0x0A, 0x0E, 0x1A), strokeWidthPx = dp(1))
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_mic)
                imageAlpha = 0xE6
            },
            LinearLayout.LayoutParams(dp(20), dp(20)),
        )
        addView(
            TextView(context).apply {
                text = getString(R.string.action_talk)
                setTextColor(CREAM)
                textSize = 15f
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
        )
        setOnClickListener { startConversation() }
    }

    private fun startConversation() {
        if (conversation?.active == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        conversation = Conversation(this, settings, this).also { it.start() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startConversation()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed the key or the cap, and the spend line is stale after a session.
        spend.text = Usage(this).label()
        // Also picks up a place that was just chosen in Settings.
        weather.start {
            weatherPage.bind(it)
            radarPage.bind(it)
            sky.bind(it.forecast, debugScene())
        }
    }

    override fun onPause() {
        super.onPause()
        conversation?.stop()
        weather.stop()
    }

    override fun onState(state: Conversation.State) {
        when (state) {
            Conversation.State.CONNECTING -> {
                captions.show(getString(R.string.state_connecting))
                talk.visibility = View.GONE
            }
            Conversation.State.LISTENING -> captions.setStatus(getString(R.string.state_listening))
            Conversation.State.ENDED -> {
                captions.hide()
                talk.visibility = View.VISIBLE
                spend.text = Usage(this).label()
            }
        }
    }

    override fun onSpend(label: String) { spend.text = label }

    override fun onUserText(text: String) = captions.appendUser(text)

    override fun onAssistantText(text: String) = captions.appendAssistant(text)

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
