package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The month as a sheet of dots, off the same calendars the model reads -- see [readCalendar].
 *
 * A filled dot is a day with something on it, an empty ring a free one, and today wears the accent.
 * That is the whole month readable from across the room without a single title on it; tapping a
 * day is what brings its titles up, in a card over the sheet.
 *
 * It lifts over the clock rather than living on a page of its own: the month is worth a glance now
 * and then, not a quarter of the swiping.
 */
class CalendarPage(context: Context) : FrameLayout(context) {
    private var month: YearMonth = YearMonth.now()
    private var events: List<CalendarEvent> = emptyList()

    private val today = text(104f, TEXT).apply {
        typeface = DISPLAY
        letterSpacing = -0.03f
        includeFontPadding = false
    }
    private val weekday = text(22f, TEXT)
    private val monthLabel = text(20f, TEXT).apply { gravity = Gravity.CENTER }
    private val message = text(14f, MUTED)
    private val grid = GridLayout(context).apply { columnCount = 7 }

    /** The day card, and the dimmed sheet behind it that puts it away when tapped. */
    private val dayTitle = text(22f, TEXT).apply { typeface = DISPLAY }
    private val dayList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val popup = FrameLayout(context).apply {
        setBackgroundColor(Color.argb(0x59, 0x16, 0x15, 0x12))
        visibility = GONE
        setOnClickListener { hideDay() }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = context.card(radius = 28)
                val pad = context.dp(28)
                setPadding(pad, context.dp(24), pad, context.dp(24))
                // A tap on the card itself is not a tap on the sheet behind it.
                isClickable = true
                addView(dayTitle)
                addView(
                    ScrollView(context).apply {
                        isVerticalScrollBarEnabled = false
                        addView(dayList)
                    },
                    LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = context.dp(14) },
                )
            },
            LayoutParams(context.dp(440), WRAP, Gravity.CENTER),
        )
    }

    init {
        setBackgroundColor(INK)
        alpha = 0f
        visibility = GONE
        // Swallows the taps that would otherwise reach the clock underneath, including the swipe
        // that would turn the page; a tap on the paper away from the dots puts the sheet away.
        setOnClickListener { hide() }

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(today)
            addView(weekday)
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(arrow("‹", R.string.calendar_previous) { turn(-1) })
                    addView(monthLabel, LinearLayout.LayoutParams(context.dp(150), WRAP))
                    addView(arrow("›", R.string.calendar_next) { turn(1) })
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(20); bottomMargin = context.dp(72) },
            )
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(message)
            addView(grid)
        }
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                // The dots sit to the right, clear of the buttons in the bottom-left corner.
                setPadding(context.dp(40), context.dp(16), context.dp(40), context.dp(16))
                addView(left, LinearLayout.LayoutParams(0, FILL, 1f))
                addView(right, LinearLayout.LayoutParams(WRAP, FILL))
            },
            LayoutParams(FILL, FILL),
        )
        addView(popup, LayoutParams(FILL, FILL))
    }

    val showing get() = visibility == VISIBLE

    /** Fades in, a little way up, on this month read afresh. */
    fun show() {
        month = YearMonth.now()
        hideDay()
        refresh()
        visibility = VISIBLE
        translationY = context.dp(14).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(220).withEndAction(null)
    }

    fun hide() {
        animate().alpha(0f).translationY(context.dp(8).toFloat()).setDuration(160)
            .withEndAction { visibility = GONE }
    }

    /** Back closes the day card first, then the sheet. */
    fun back(): Boolean {
        if (popup.visibility == VISIBLE) hideDay() else hide()
        return true
    }

    private fun turn(by: Long) {
        month = month.plusMonths(by)
        refresh()
    }

    /** Reads the month off the main thread and redraws. */
    private fun refresh() {
        val now = LocalDate.now()
        today.text = now.dayOfMonth.toString()
        weekday.text = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        monthLabel.text = month.format(
            DateTimeFormatter.ofPattern(if (Locale.getDefault().language == "ja") "y年M月" else "MMMM y"),
        )
        events = emptyList()
        drawMonth()
        if (!calendarPermitted(context)) {
            say(context.getString(R.string.calendar_no_permission))
            return
        }
        val asked = month
        thread {
            val read = readCalendar(context, asked.lengthOfMonth(), limit = MONTH_LIMIT, start = asked.atDay(1))
            post {
                // A second tap on the arrow may have moved on while this was reading.
                if (asked != month) return@post
                if (read == null) say(context.getString(R.string.calendar_unavailable))
                else { say(""); events = read; drawMonth() }
            }
        }
    }

    private fun say(text: String) {
        message.text = text
        message.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    private fun drawMonth() {
        grid.removeAllViews()
        // Monday first, as the week is lived rather than as the calendar app prints it.
        for (day in DayOfWeek.entries) {
            grid.addView(text(12f, MUTED).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
            }, cell(context.dp(20)))
        }
        repeat(month.atDay(1).dayOfWeek.value - 1) { grid.addView(View(context), cell()) }
        val now = LocalDate.now()
        for (dayOfMonth in 1..month.lengthOfMonth()) {
            val date = month.atDay(dayOfMonth)
            val busy = eventsOn(events, date).isNotEmpty()
            grid.addView(dot(date, busy, date == now, date.isBefore(now)), cell())
        }
    }

    private fun dot(date: LocalDate, busy: Boolean, isToday: Boolean, past: Boolean) = TextView(context).apply {
        text = date.dayOfMonth.toString()
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                isToday -> setColor(ACCENT)
                busy -> setColor(TEXT)
                else -> setStroke(context.dp(2), Color.argb(0x33, 0x16, 0x15, 0x12))
            }
        }
        setTextColor(if (isToday) ON_ACCENT else if (busy) INK else MUTED)
        // What has gone by is still there to look back at, just quieter than what is to come.
        alpha = if (past && !isToday) 0.4f else 1f
        contentDescription = dayLabel(date)
        setOnClickListener { showDay(date) }
    }

    private fun showDay(date: LocalDate) {
        dayTitle.text = dayLabel(date)
        dayList.removeAllViews()
        val on = eventsOn(events, date)
        if (on.isEmpty()) dayList.addView(text(15f, MUTED).apply { text = context.getString(R.string.calendar_none) })
        for (event in on) dayList.addView(row(event))
        popup.alpha = 0f
        popup.visibility = VISIBLE
        popup.animate().alpha(1f).setDuration(160)
    }

    private fun hideDay() {
        popup.visibility = GONE
    }

    private fun row(event: CalendarEvent) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(7), 0, context.dp(7))
        addView(
            text(14f, MUTED).apply {
                text = event.start?.format(CLOCK) ?: context.getString(R.string.calendar_all_day)
            },
            LinearLayout.LayoutParams(context.dp(64), WRAP),
        )
        addView(
            text(17f, TEXT).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                text = event.title
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(8) },
        )
    }

    private fun arrow(glyph: String, description: Int, click: () -> Unit) = text(28f, TEXT).apply {
        text = glyph
        gravity = Gravity.CENTER
        contentDescription = context.getString(description)
        setOnClickListener { click() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)) }

    private fun dayLabel(date: LocalDate): String = context.getString(
        R.string.calendar_date,
        date.monthValue,
        date.dayOfMonth,
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
    )

    private fun text(size: Float, colour: Int) = TextView(context).apply {
        textSize = size
        setTextColor(colour)
    }

    private fun cell(height: Int = context.dp(DOT)) = GridLayout.LayoutParams().apply {
        width = context.dp(DOT)
        this.height = height
        val gap = context.dp(3)
        setMargins(gap, gap, gap, gap)
    }

    private companion object {
        /** Six weeks of these and a row of initials fit the height of a 5-inch screen. */
        const val DOT = 34
        /** A month of a busy household, which the tool's own cap for reading aloud would cut short. */
        const val MONTH_LIMIT = 400
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}
