package com.chrisb588.easlie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Stroke-only marks keep the canvas visible; resize marks have no outline. */
internal class FloatingWindowAnchor(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { DRAG, RESIZE_LEFT, RESIZE_RIGHT }

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var foreground = 0
    private var contrast = 0
    private fun dp(value: Int) = value * resources.displayMetrics.density

    fun setColors(foreground: Int, contrast: Int) {
        this.foreground = foreground
        this.contrast = contrast
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        path.reset()
        if (kind == Kind.DRAG) {
            path.moveTo(dp(12), dp(8))
            path.lineTo(w - dp(12), dp(8))
        } else {
            val left = kind == Kind.RESIZE_LEFT
            val centerX = if (left) dp(32) else w - dp(32)
            val centerY = h - dp(32)
            val radius = dp(24) // Follow the window's 32dp corner, inset by 8dp.
            val edgeX = if (left) dp(8) else w - dp(8)
            path.moveTo(edgeX, centerY - dp(4))
            path.lineTo(edgeX, centerY)
            path.arcTo(centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                if (left) 180f else 0f, if (left) -90f else 90f, false)
            path.lineTo(if (left) centerX + dp(4) else centerX - dp(4), h - dp(8))
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (kind == Kind.DRAG) {
            paint.color = contrast
            paint.strokeWidth = dp(7)
            canvas.drawPath(path, paint)
        }
        paint.color = foreground
        paint.strokeWidth = dp(4)
        canvas.drawPath(path, paint)
    }
}
