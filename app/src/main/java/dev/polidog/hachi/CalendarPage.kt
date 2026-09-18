package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The week ahead, off the same calendars the model reads -- see [readCalendar].
 *
 * It lifts over the clock rather than living on a page of its own: what is on this week is worth a
 * glance now and then, not a quarter of the swiping.
 */
class CalendarPage(context: Context) : LinearLayout(context) {
    private val header = TextView(context).apply {
        setTextColor(MUTED)
        textSize = 11f
        letterSpacing = 0.18f
        text = context.getString(R.string.calendar_title)
    }
    private val message = TextView(context).apply {
        setTextColor(MUTED)
        textSize = 14f
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val list = LinearLayout(context).apply {
        orientation = VERTICAL
        background = context.card()
        val pad = context.dp(18)
        setPadding(pad, context.dp(10), pad, context.dp(10))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        // So the list covers the whole viewport and a tap on the empty half below it still lands.
        isFillViewport = true
        addView(list, LayoutParams(FILL, FILL))
    }

    init {
        orientation = VERTICAL
        // Dark enough to read a list over the sky, and it swallows the taps that would otherwise
        // land on the clock page underneath -- including the swipe that would turn the page.
        setBackgroundColor(Color.argb(0xF2, 0x0C, 0x0E, 0x0C))
        alpha = 0f
        visibility = GONE
        val side = context.dp(28)
        // The buttons that opened it stay live underneath, so the list stops above them.
        setPadding(side, context.dp(16), side, context.dp(84))
        addView(header)
        addView(message, LayoutParams(FILL, 0, 1f))
        addView(scroll, LayoutParams(FILL, 0, 1f).apply { topMargin = context.dp(10) })
        // A tap anywhere puts it away again. It goes on the list rather than on the scroll view
        // around it: ScrollView never calls its own click listener, and a tap that turns into a
        // scroll is intercepted before it gets this far, so scrolling still does not dismiss.
        val away = OnClickListener { hide() }
        setOnClickListener(away)
        list.setOnClickListener(away)
    }

    val showing get() = visibility == VISIBLE

    /** Fades in, a little way up, with this week read afresh. */
    fun show() {
        refresh()
        visibility = VISIBLE
        translationY = context.dp(14).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(220).withEndAction(null)
    }

    fun hide() {
        animate().alpha(0f).translationY(context.dp(8).toFloat()).setDuration(160)
            .withEndAction { visibility = GONE }
    }

    /** Reads the calendar off the main thread and redraws. */
    private fun refresh() {
        if (!calendarPermitted(context)) {
            showMessage(context.getString(R.string.calendar_no_permission))
            return
        }
        thread {
            val events = readCalendar(context, DAYS)
            post { bind(events) }
        }
    }

    private fun bind(events: List<CalendarEvent>?) {
        when {
            events == null -> showMessage(context.getString(R.string.calendar_unavailable))
            events.isEmpty() -> showMessage(context.getString(R.string.calendar_none))
            else -> {
                message.visibility = GONE
                list.removeAllViews()
                var day: LocalDate? = null
                for (event in events) {
                    // The date is written once per day, so a day with four things on it reads as one
                    // block rather than as the same date four times.
                    list.addView(row(event, newDay = event.date != day))
                    day = event.date
                }
            }
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = VISIBLE
        list.removeAllViews()
    }

    private fun row(event: CalendarEvent, newDay: Boolean) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(if (newDay) 8 else 3), 0, context.dp(3))
        addView(
            TextView(context).apply {
                setTextColor(MUTED)
                textSize = 13f
                text = if (newDay) dayLabel(event.date) else ""
                setTextColor(if (newDay) LIME else MUTED)
            },
            LayoutParams(context.dp(96), WRAP),
        )
        addView(
            TextView(context).apply {
                setTextColor(MUTED)
                textSize = 13f
                text = event.start?.format(CLOCK) ?: context.getString(R.string.calendar_all_day)
            },
            LayoutParams(context.dp(56), WRAP),
        )
        addView(
            TextView(context).apply {
                setTextColor(TEXT)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                text = event.title
            },
            LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(8) },
        )
    } as View

    private fun dayLabel(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> context.getString(R.string.weather_today)
            today.plusDays(1) -> context.getString(R.string.weather_tomorrow)
            else -> context.getString(
                R.string.calendar_date,
                date.monthValue,
                date.dayOfMonth,
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            )
        }
    }

    private companion object {
        /** A wall is read at a glance: the week ahead, not the month. */
        const val DAYS = 7
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}
