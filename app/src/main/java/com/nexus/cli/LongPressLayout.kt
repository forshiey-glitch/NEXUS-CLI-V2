package com.nexus.cli

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root halaman Quick Launch. Mendeteksi tekan lama tepat 300ms (GestureDetector bawaan hanya 500ms)
 * tanpa mengganggu klik tile maupun swipe ViewPager2.
 */
class LongPressLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onLongPress: (() -> Unit)? = null
    var longPressMs: Long = 10000L

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    private val trigger = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                handler.removeCallbacks(trigger)
                handler.postDelayed(trigger, longPressMs)
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(trigger)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL ->
                handler.removeCallbacks(trigger)
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(trigger)
                // Cegah klik tile tepat setelah tekan lama.
                if (fired) ev.action = MotionEvent.ACTION_CANCEL
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(trigger)
        super.onDetachedFromWindow()
    }
}