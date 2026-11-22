package com.nexus.cli

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val values = ArrayDeque<Float>()
    private val linePath = Path()
    private val fillPath = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = DEFAULT_COLOR
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = 0x22FFFFFF
        strokeWidth = 1f
    }

    /** Jumlah titik maksimum yang disimpan. */
    var maxPoints: Int = 60

    /** Nilai maksimum sumbu Y. Isi null untuk auto-scale (mis. network). */
    var fixedMax: Float? = 100f

    var lineColor: Int = DEFAULT_COLOR
        set(value) {
            field = value
            linePaint.color = value
            rebuildShader()
            invalidate()
        }

    fun addValue(value: Float) {
        values.addLast(value)
        while (values.size > maxPoints) values.removeFirst()
        invalidate()
    }

    fun clearValues() {
        values.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    private fun rebuildShader() {
        if (height <= 0) return
        val top = (lineColor and 0x00FFFFFF) or 0x66000000
        val bottom = lineColor and 0x00FFFFFF
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)

        if (values.size < 2) return

        val maxV = fixedMax ?: ((values.maxOrNull() ?: 1f) * 1.2f).coerceAtLeast(1f)
        val step = w / (maxPoints - 1).coerceAtLeast(1)
        val startX = w - (values.size - 1) * step

        linePath.reset()
        fillPath.reset()

        values.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = h - (v.coerceIn(0f, maxV) / maxV) * (h - 4f) - 2f
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }

    private companion object {
        val DEFAULT_COLOR: Int = Color.parseColor("#00FF66")
    }
}