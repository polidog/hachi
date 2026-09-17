package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The overlay shown while a conversation runs: a status line, both sides' live captions, and an end
 * button. Tapping anywhere else hushes the reply -- the device cannot be talked over (see the
 * half-duplex gate in [Conversation]), so this is the only way to cut Hachi off mid-sentence.
 */
class ConversationView(context: Context) : FrameLayout(context) {
    var onHush: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null

    private val status = TextView(context).apply {
        setTextColor(CREAM_60)
        textSize = 13f
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
    }
    private val user = caption(Color.argb(0xB3, 0xFA, 0xF6, 0xEC), Typeface.NORMAL)
    private val assistant = caption(CREAM, Typeface.BOLD)

    init {
        setBackgroundColor(Color.argb(0xE6, 0x08, 0x0A, 0x12))
        isClickable = true // swallow taps so they never reach the clock underneath
        setOnClickListener { onHush?.invoke() }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val side = context.dp(28)
                setPadding(side, 0, side, 0)
                addView(status)
                addView(user)
                addView(assistant)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        addView(
            TextView(context).apply {
                text = context.getString(R.string.hint_tap_to_hush)
                setTextColor(Color.argb(0x66, 0xFA, 0xF6, 0xEC))
                textSize = 11f
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { leftMargin = context.dp(18); bottomMargin = context.dp(18) },
        )

        addView(
            TextView(context).apply {
                text = context.getString(R.string.action_end)
                setTextColor(CREAM)
                textSize = 14f
                val h = context.dp(16)
                val v = context.dp(10)
                setPadding(h, v, h, v)
                background = pill(context.dp(20).toFloat(), strokeWidthPx = context.dp(1))
                setOnClickListener { onEnd?.invoke() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { rightMargin = context.dp(18); bottomMargin = context.dp(14) },
        )

        visibility = GONE
    }

    private fun caption(color: Int, style: Int) = TextView(context).apply {
        setTextColor(color)
        setTypeface(null, style)
        textSize = 19f
        gravity = Gravity.CENTER
        val gap = context.dp(5)
        setPadding(0, gap, 0, gap)
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
