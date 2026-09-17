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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Conversation.Ui {
    private lateinit var settings: Settings
    private lateinit var captions: ConversationView
    private lateinit var spend: TextView
    private lateinit var talk: View
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

        setContentView(
            FrameLayout(this).apply {
                addView(ClockView(context))
                addView(
                    spend,
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
                        .apply { leftMargin = dp(18); bottomMargin = dp(16) },
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
        background = pill(dp(24).toFloat(), strokeWidthPx = dp(1))
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
    }

    override fun onPause() {
        super.onPause()
        conversation?.stop()
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
