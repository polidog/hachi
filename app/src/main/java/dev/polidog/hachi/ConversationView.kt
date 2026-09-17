package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The overlay shown while a conversation runs: a status line plus both sides' live captions. */
class ConversationView(context: Context) : LinearLayout(context) {
    private val status = TextView(context).apply {
        setTextColor(Color.argb(0xAA, 0xFF, 0xFF, 0xFF))
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val user = caption(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
    private val assistant = caption(Color.WHITE).apply { setTypeface(null, Typeface.BOLD) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(0xE0, 0x08, 0x0A, 0x10))
        val pad = (context.resources.displayMetrics.density * 24).toInt()
        setPadding(pad, pad, pad, pad)
        addView(status)
        addView(user)
        addView(assistant)
        visibility = GONE
    }

    private fun caption(color: Int) = TextView(context).apply {
        setTextColor(color)
        textSize = 20f
        gravity = Gravity.CENTER
        val pad = (context.resources.displayMetrics.density * 6).toInt()
        setPadding(0, pad, 0, pad)
    }

    fun show(statusText: String) {
        status.text = statusText
        user.text = ""
        assistant.text = ""
        visibility = View.VISIBLE
    }

    fun setStatus(text: String) { status.text = text }

    fun appendUser(text: String) {
        // A new user turn starts once the assistant has answered the previous one.
        if (assistant.text.isNotEmpty()) { user.text = ""; assistant.text = "" }
        user.text = context.getString(R.string.speaker_user) + ": " + trimmed(user.text, text)
    }

    fun appendAssistant(text: String) {
        assistant.text = context.getString(R.string.speaker_assistant) + ": " + trimmed(assistant.text, text)
    }

    /** Transcripts arrive as fragments; keep only the tail so the two lines never overflow. */
    private fun trimmed(current: CharSequence, addition: String): String {
        val body = current.toString().substringAfter(": ", current.toString()) + addition
        return if (body.length > 120) "…" + body.takeLast(120) else body
    }

    fun hide() { visibility = GONE }
}
