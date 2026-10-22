package com.axelestrada.indriveinspector.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Anillo de progreso circular (el mismo lenguaje visual que la app).
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var ringColor: Int = 0xFF35D39A.toInt()
        set(value) {
            field = value
            progressPaint.color = value
            invalidate()
        }

    var trackColor: Int = 0x1AFFFFFF
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    var ringWidth: Float = 0f
        set(value) {
            field = value
            trackPaint.strokeWidth = value
            progressPaint.strokeWidth = value
            requestLayout()
            invalidate()
        }

    init {
        trackPaint.color = trackColor
        progressPaint.color = ringColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = if (ringWidth > 0f) ringWidth else width * 0.11f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val inset = stroke / 2f
        arcBounds.set(inset, inset, width - inset, height - inset)

        canvas.drawArc(arcBounds, -90f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcBounds, -90f, 360f * progress, false, progressPaint)
        }
    }
}
