package dev.polidog.hachi

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Scroller
import kotlin.math.abs

/**
 * A horizontal pager: full-width pages, swipe or fling to move between them.
 *
 * Written by hand because the app carries no AndroidX; ViewPager2 would be the only reason to add
 * it. Pages are laid out side by side and the group is scrolled, which is all this needs -- there is
 * no adapter, no recycling, and never more than a handful of pages.
 */
class PagerView(context: Context) : ViewGroup(context) {
    var onPageChanged: ((Int) -> Unit)? = null

    var page: Int = 0
        private set

    private val scroller = Scroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var velocity: VelocityTracker? = null
    private var lastX = 0f
    private var downX = 0f
    private var dragging = false

    fun addPage(view: View) = addView(view)

    fun goTo(index: Int, smooth: Boolean = true) {
        val target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val destination = target * width
        if (smooth && width > 0) {
            scroller.startScroll(scrollX, 0, destination - scrollX, 0, 300)
            invalidate()
        } else {
            scrollTo(destination, 0)
        }
        if (target != page) {
            page = target
            onPageChanged?.invoke(target)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pageWidth = r - l
        for (i in 0 until childCount) {
            getChildAt(i).layout(i * pageWidth, 0, (i + 1) * pageWidth, b - t)
        }
        // A rotation or first layout must leave the current page where it belongs.
        scrollTo(page * pageWidth, 0)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                dragging = false
                if (!scroller.isFinished) scroller.abortAnimation()
            }
            MotionEvent.ACTION_MOVE ->
                // Only steal the gesture once it is clearly a horizontal drag, so taps and any
                // vertical scrolling inside a page still reach the page itself.
                if (abs(event.x - downX) > touchSlop) dragging = true
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                if (!scroller.isFinished) scroller.abortAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    val dx = (lastX - event.x).toInt()
                    val limit = (childCount - 1) * width
                    // Refuse to drag past either end rather than rubber-banding into empty space.
                    scrollTo((scrollX + dx).coerceIn(0, maxOf(limit, 0)), 0)
                    lastX = event.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker.computeCurrentVelocity(1000)
                val vx = tracker.xVelocity
                val target = when {
                    vx < -minFlingVelocity -> page + 1
                    vx > minFlingVelocity -> page - 1
                    width > 0 -> (scrollX + width / 2) / width
                    else -> page
                }
                goTo(target)
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }
}
