package dev.polidog.hachi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Conversation.Ui {
    private lateinit var settings: Settings
    private lateinit var captions: ConversationView
    private lateinit var spend: TextView
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

        captions = ConversationView(this)
        spend = TextView(this).apply {
            setTextColor(Color.argb(0x66, 0xFF, 0xFF, 0xFF))
            textSize = 11f
            gravity = Gravity.BOTTOM or Gravity.END
            val pad = (resources.displayMetrics.density * 8).toInt()
            setPadding(pad, pad, pad, pad)
            text = Usage(this@MainActivity).monthLabel()
        }
        val root = FrameLayout(this).apply {
            addView(ClockView(context))
            addView(spend)
            addView(captions)
            setOnClickListener { toggleConversation() }
            setOnLongClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); true
            }
        }
        setContentView(root)
    }

    private fun toggleConversation() {
        val running = conversation
        if (running != null && running.active) {
            // Tapping while Hachi talks shuts it up; tapping when it is quiet ends the conversation.
            if (running.speaking) running.hush() else running.stop()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        conversation = Conversation(this, settings, this).also { it.start() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleConversation()
    }

    override fun onPause() {
        super.onPause()
        conversation?.stop()
    }

    override fun onState(state: Conversation.State) {
        when (state) {
            Conversation.State.CONNECTING -> captions.show(getString(R.string.state_connecting))
            Conversation.State.LISTENING -> captions.setStatus(getString(R.string.state_listening))
            Conversation.State.ENDED -> captions.hide()
        }
    }

    override fun onSpend(label: String) { spend.text = label }

    override fun onUserText(text: String) = captions.appendUser(text)

    override fun onAssistantText(text: String) = captions.appendAssistant(text)

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
