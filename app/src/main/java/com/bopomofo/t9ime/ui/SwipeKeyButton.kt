package com.bopomofo.t9ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs

/**
 * 支援 Tap (點擊，含快速連點) 與 4 方向 Swipe (滑動) 的自訂按鍵
 */
class SwipeKeyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatButton(context, attrs, defStyleAttr) {

    enum class Direction {
        LEFT, RIGHT, UP, DOWN
    }

    var onTapListener: (() -> Unit)? = null
    var onSwipeListener: ((Direction) -> Unit)? = null
    var onLongClickListenerCustom: (() -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var isMoved = false
    private var isLongPressedTriggered = false
    private val SWIPE_DISTANCE_THRESHOLD = 40f
    private val LONG_PRESS_TIMEOUT = 350L

    private val longPressRunnable = Runnable {
        if (!isMoved && isPressed) {
            isLongPressedTriggered = true
            onLongClickListenerCustom?.invoke()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isMoved = false
                isLongPressedTriggered = false
                isPressed = true
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (abs(dx) > SWIPE_DISTANCE_THRESHOLD || abs(dy) > SWIPE_DISTANCE_THRESHOLD) {
                    isMoved = true
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                val dx = event.x - startX
                val dy = event.y - startY

                if (isLongPressedTriggered) {
                    // 長按已經處理完畢
                    return true
                }

                if (isMoved && (abs(dx) > SWIPE_DISTANCE_THRESHOLD || abs(dy) > SWIPE_DISTANCE_THRESHOLD)) {
                    // 觸發滑動 (Swipe)
                    if (abs(dx) > abs(dy)) {
                        if (dx > 0) onSwipeListener?.invoke(Direction.RIGHT)
                        else onSwipeListener?.invoke(Direction.LEFT)
                    } else {
                        if (dy > 0) onSwipeListener?.invoke(Direction.DOWN)
                        else onSwipeListener?.invoke(Direction.UP)
                    }
                } else {
                    // 只要放開即視為一次點擊，絕不丟失任何一次按鍵！
                    performClick()
                    onTapListener?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
