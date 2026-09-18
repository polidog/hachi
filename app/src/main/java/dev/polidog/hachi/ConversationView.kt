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
 * The overlay shown while a conversation runs: the orb, a status line, both sides' live captions,
 * and an end button. Tapping anywhere else hushes the reply -- the device cannot be talked over (see
 * the half-duplex gate in [Conversation]), so this is the only way to cut Hachi off mid-sentence.
 *
 * Neither side is labelled. Who said what is carried by the type itself -- what was heard is set
 * small and quiet, what was answered is set large and bold -- which reads across a room, where a name
 * repeated in front of every line does not.
 */
class ConversationView(context: Context) : FrameLayout(context) {
    var onHush: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null

    private val status = TextView(context).apply {
        setTextColor(MUTED)
        textSize = 13f
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
    }
    private val user = caption(MUTED, Typeface.NORMAL, 15f)
    // Read from across the room, so as large as the reply: "2番" is only an answer if "2" can be seen.
    private val choices = caption(Color.rgb(0xF2, 0xF0, 0xEB), Typeface.NORMAL, 26f).apply {
        gravity = Gravity.START
        visibility = GONE
    }
    // Not TEXT: the rest of the app is dark type on paper, and this is the one place that is night.
    private val assistant = caption(Color.rgb(0xF2, 0xF0, 0xEB), Typeface.BOLD, 24f)

    init {
        // Black, and opaque: the sky and the clock behind this have no business showing through,
        // and the orb only reads as light if there is nothing else lit on the screen.
        setBackgroundColor(Color.BLACK)
        isClickable = true // swallow taps so they never reach the clock underneath
        setOnClickListener { onHush?.invoke() }

        // The orb has the top of the screen to itself and the captions sit under it, the way the
        // reference picture reads: a face first, then what was said.
        addView(OrbView(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val side = context.dp(28)
                setPadding(side, 0, side, context.dp(42))
                addView(choices, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = context.dp(24) })
                addView(status)
                addView(user)
                addView(assistant)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        addView(
            TextView(context).apply {
                text = context.getString(R.string.hint_tap_to_hush)
                setTextColor(MUTED)
                textSize = 11f
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { leftMargin = context.dp(18); bottomMargin = context.dp(18) },
        )

        addView(
            TextView(context).apply {
                text = context.getString(R.string.action_end)
                setTextColor(TEXT)
                textSize = 14f
                val h = context.dp(16)
                val v = context.dp(10)
                setPadding(h, v, h, v)
                background = context.card(radius = 20)
                setOnClickListener { onEnd?.invoke() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { rightMargin = context.dp(18); bottomMargin = context.dp(14) },
        )

        visibility = GONE
    }

    private fun caption(color: Int, style: Int, size: Float) = TextView(context).apply {
        setTextColor(color)
        setTypeface(null, style)
        textSize = size
        gravity = Gravity.CENTER
        val gap = context.dp(5)
        setPadding(0, gap, 0, gap)
    }

    fun show(statusText: String) {
        status.text = statusText
        user.text = ""
        assistant.text = ""
        showChoices(emptyList())
        visibility = View.VISIBLE
    }

    fun showChoices(names: List<String>) {
        choices.text = names.mapIndexed { i, it -> "${i + 1}   $it" }.joinToString("\n")
        choices.visibility = if (names.isEmpty()) GONE else VISIBLE
    }

    fun setStatus(text: String) { status.text = text }

    fun appendUser(text: String) {
        // A new user turn starts once the assistant has answered the previous one.
        if (assistant.text.isNotEmpty()) { user.text = ""; assistant.text = "" }
        user.text = trimmed(user.text, text)
    }

    fun appendAssistant(text: String) {
        assistant.text = trimmed(assistant.text, text)
    }

    /** Transcripts arrive as fragments; keep only the tail so the lines never overflow. */
    private fun trimmed(current: CharSequence, addition: String): String {
        val body = current.toString() + addition
        return if (body.length > 120) "…" + body.takeLast(120) else body
    }

    fun hide() { visibility = GONE }
}
