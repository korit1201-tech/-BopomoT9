package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.bopomofo.t9ime.R
import kotlin.math.abs

/**
 * 支援 Tap (點擊) 與 4 方向長拉拖選 (Drag-to-select Swipe) 的自訂按鍵，
 * 內建 4 向十字指示羅盤與動態縮放高亮視覺動畫反饋。
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

    /**
     * 提供四方向字符映射表（由 Service 綁定，若有提供則啟用拖動視覺指示動畫）
     */
    var swipeLabelsProvider: (() -> Map<Direction, String>)? = null

    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var isMoved = false
    private var activeDirection: Direction? = null

    companion object {
        private const val SWIPE_THRESHOLD_DP = 26f
        private const val SHOW_POPUP_DELAY_MS = 220L
    }

    private val swipeThresholdPx: Float
        get() = SWIPE_THRESHOLD_DP * resources.displayMetrics.density

    // 視覺預覽浮動視窗
    private var previewPopup: PopupWindow? = null
    private var popupView: View? = null
    private var tvUp: TextView? = null
    private var tvDown: TextView? = null
    private var tvLeft: TextView? = null
    private var tvRight: TextView? = null

    private val showPopupRunnable = Runnable {
        if (isPressed) {
            showPreviewPopup()
        }
    }

    private fun initPopupView() {
        if (previewPopup != null) return
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.layout_swipe_preview, null)
            popupView = view
            tvUp = view.findViewById(R.id.tv_swipe_up)
            tvDown = view.findViewById(R.id.tv_swipe_down)
            tvLeft = view.findViewById(R.id.tv_swipe_left)
            tvRight = view.findViewById(R.id.tv_swipe_right)

            val density = resources.displayMetrics.density
            val sizePx = (132 * density).toInt()
            previewPopup = PopupWindow(view, sizePx, sizePx, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isClippingEnabled = false
                animationStyle = 0
            }
        } catch (_: Exception) {
        }
    }

    private fun showPreviewPopup() {
        val labels = swipeLabelsProvider?.invoke() ?: return
        if (labels.isEmpty()) return

        initPopupView()
        val popup = previewPopup ?: return
        val view = popupView ?: return

        tvUp?.text = labels[Direction.UP] ?: ""
        tvDown?.text = labels[Direction.DOWN] ?: ""
        tvLeft?.text = labels[Direction.LEFT] ?: ""
        tvRight?.text = labels[Direction.RIGHT] ?: ""

        resetPopupItemStates()

        try {
            if (!popup.isShowing && windowToken != null) {
                val loc = IntArray(2)
                getLocationInWindow(loc)
                val density = resources.displayMetrics.density
                val popupSize = (132 * density).toInt()
                val xOff = loc[0] + (width - popupSize) / 2
                val yOff = loc[1] - popupSize - (10 * density).toInt() // 浮在按鍵上方

                // 彈入微動態動畫
                view.scaleX = 0.85f
                view.scaleY = 0.85f
                view.alpha = 0f
                popup.showAtLocation(this, Gravity.NO_GRAVITY, xOff, yOff)
                view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(100).start()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateActiveDirection(newDir: Direction?) {
        if (activeDirection == newDir) return
        activeDirection = newDir

        // 觸覺微反饋
        if (newDir != null) {
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {
            }
        }

        val applyState = { tv: TextView?, isTarget: Boolean ->
            if (tv == null || tv.text.isNullOrEmpty()) {
                tv?.visibility = View.INVISIBLE
            } else {
                tv.visibility = View.VISIBLE
                if (isTarget) {
                    tv.setBackgroundResource(R.drawable.bg_swipe_item_active)
                    tv.setTextColor(Color.WHITE)
                    tv.animate().scaleX(1.35f).scaleY(1.35f).alpha(1.0f).setDuration(90).start()
                } else {
                    tv.background = null
                    val normalColor = androidx.core.content.ContextCompat.getColor(context, R.color.kb_text_primary)
                    tv.setTextColor(normalColor)
                    tv.animate().scaleX(1.0f).scaleY(1.0f).alpha(if (newDir == null) 1.0f else 0.4f).setDuration(90).start()
                }
            }
        }

        applyState(tvUp, newDir == Direction.UP)
        applyState(tvDown, newDir == Direction.DOWN)
        applyState(tvLeft, newDir == Direction.LEFT)
        applyState(tvRight, newDir == Direction.RIGHT)
    }

    private fun resetPopupItemStates() {
        val normalColor = androidx.core.content.ContextCompat.getColor(context, R.color.kb_text_primary)
        val reset = { tv: TextView? ->
            if (tv != null) {
                tv.background = null
                tv.setTextColor(normalColor)
                tv.scaleX = 1.0f
                tv.scaleY = 1.0f
                tv.alpha = 1.0f
            }
        }
        reset(tvUp)
        reset(tvDown)
        reset(tvLeft)
        reset(tvRight)
    }

    private fun dismissPreviewPopup() {
        removeCallbacks(showPopupRunnable)
        val popup = previewPopup
        if (popup != null && popup.isShowing) {
            try {
                popup.dismiss()
            } catch (_: Exception) {
            }
        }
        activeDirection = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                downTime = System.currentTimeMillis()
                isMoved = false
                activeDirection = null
                isPressed = true

                // 按鍵按壓微縮動畫
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(50).start()

                // 長按 220ms 展開十字指南針預覽（快速敲擊 <220ms 絕不喚起彈窗）
                removeCallbacks(showPopupRunnable)
                postDelayed(showPopupRunnable, SHOW_POPUP_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val threshold = swipeThresholdPx

                if (abs(dx) > threshold || abs(dy) > threshold) {
                    isMoved = true
                    if (previewPopup == null || previewPopup?.isShowing == false) {
                        showPreviewPopup()
                    }
                    val isDominantX = abs(dx) > abs(dy)
                    val dir = if (isDominantX) {
                        if (dx > 0) Direction.RIGHT else Direction.LEFT
                    } else {
                        if (dy > 0) Direction.DOWN else Direction.UP
                    }
                    updateActiveDirection(dir)
                } else {
                    updateActiveDirection(null)
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
                val selectedDir = activeDirection

                // 核心關鍵：立即同步派發輸入事件（0 毫秒延遲，絕不等待動畫或異步回呼，杜絕快打時按鍵順序顛倒）
                if (selectedDir != null) {
                    onSwipeListener?.invoke(selectedDir)
                } else {
                    performClick()
                    onTapListener?.invoke()
                }

                // 立即關閉視覺預覽浮層
                dismissPreviewPopup()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
                dismissPreviewPopup()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(showPopupRunnable)
        try {
            previewPopup?.dismiss()
        } catch (_: Exception) {
        }
        previewPopup = null
        popupView = null
    }
}
