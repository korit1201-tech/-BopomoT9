package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 輕量化手寫畫布 View
 * 支援平滑筆畫繪製、定時自動識別觸發、以及自適應特徵比對辨識
 */
class HandwritingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class StrokePoint(val x: Float, val y: Float, val time: Long)

    var onRecognizeListener: ((List<List<StrokePoint>>) -> Unit)? = null

    private val paint = Paint().apply {
        color = Color.parseColor("#E65100")
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val currentPath = Path()
    private val strokes = mutableListOf<List<StrokePoint>>()
    private val currentStroke = mutableListOf<StrokePoint>()
    private val paths = mutableListOf<Path>()

    private val handler = Handler(Looper.getMainLooper())
    private val recognizeRunnable = Runnable {
        if (strokes.isNotEmpty()) {
            onRecognizeListener?.invoke(strokes.toList())
        }
    }
    private val AUTO_RECOGNIZE_DELAY = 600L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val time = System.currentTimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(recognizeRunnable)
                currentPath.reset()
                currentPath.moveTo(x, y)
                currentStroke.clear()
                currentStroke.add(StrokePoint(x, y, time))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                currentStroke.add(StrokePoint(x, y, time))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                currentStroke.add(StrokePoint(x, y, time))
                strokes.add(currentStroke.toList())
                paths.add(Path(currentPath))
                currentPath.reset()
                currentStroke.clear()
                invalidate()
                handler.postDelayed(recognizeRunnable, AUTO_RECOGNIZE_DELAY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                currentPath.reset()
                currentStroke.clear()
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 繪製格線引導輔助線
        val w = width.toFloat()
        val h = height.toFloat()
        val gridPaint = Paint().apply {
            color = Color.parseColor("#2A2A2A")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(w / 2, 0f, w / 2, h, gridPaint)
        canvas.drawLine(0f, h / 2, w, h / 2, gridPaint)

        for (p in paths) {
            canvas.drawPath(p, paint)
        }
        canvas.drawPath(currentPath, paint)
    }

    fun clearCanvas() {
        handler.removeCallbacks(recognizeRunnable)
        strokes.clear()
        paths.clear()
        currentPath.reset()
        currentStroke.clear()
        invalidate()
    }
}
