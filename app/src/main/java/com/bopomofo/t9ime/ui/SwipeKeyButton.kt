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
    private var downTime = 0L
    private var isMoved = false
    private var isLongPressedTriggered = false
    private val SWIPE_THRESHOLD_DP = 28f // 28dp (約 75~85px)，防止快打拇指微移誤觸滑動
    private val LONG_PRESS_TIMEOUT = 350L

    private val swipeThresholdPx: Float
        get() = SWIPE_THRESHOLD_DP * resources.displayMetrics.density

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
                downTime = System.currentTimeMillis()
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
                val threshold = swipeThresholdPx
                if (abs(dx) > threshold || abs(dy) > threshold) {
                    isMoved = true
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                val dx = event.x - startX
                val dy = event.y - startY
                val threshold = swipeThresholdPx
                val elapsed = System.currentTimeMillis() - downTime

                if (isLongPressedTriggered) {
                    // 長按已經處理完畢
                    return true
                }

                // 判斷是否為真正的滑動（Swipe）：
                // 1. 按壓時間超過 80ms（極快速彈起一律視為點擊，杜絕連打誤觸）
                // 2. 位移距離超過 dp 門檻
                // 3. 主軸位移需至少為次軸位移的 1.3 倍（斜向微動不觸發滑動）
                val isDominantX = abs(dx) > abs(dy) * 1.3f
                val isDominantY = abs(dy) > abs(dx) * 1.3f
                val isSwipe = elapsed >= 80L && isMoved && (
                    (abs(dx) > threshold && isDominantX) || (abs(dy) > threshold && isDominantY)
                )

                if (isSwipe) {
                    // 觸發滑動 (Swipe)
                    if (isDominantX) {
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
