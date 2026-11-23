package com.nexus.cli

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Hujan karakter 0/1 hijau neon. Setiap 100ms satu langkah turun per kolom. */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        typeface = Typeface.MONOSPACE
        textSize = 14f * context.resources.displayMetrics.density
    }

    private val trail = 16
    private var cellW = 1f
    private var cellH = 1f
    private var cols = 0
    private var rows = 0
    private var glyphs = CharArray(0)
    private var ages = IntArray(0)
    private var heads = IntArray(0)

    init {
        isClickable = false
    }

    fun startMatrixRain() {
        if (isRunning) return
        visibility = VISIBLE
        resetGrid()
        job = scope.launch {
            while (isActive) {
                tick()
                delay(100L)
            }
        }
    }

    fun stopMatrixRain() {
        job?.cancel()
        job = null
        visibility = GONE
        resetGrid()
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellW = paint.measureText("0").coerceAtLeast(1f) + 4f
        cellH = paint.textSize * 1.2f
        cols = (w / cellW).toInt().coerceAtLeast(1)
        rows = (h / cellH).toInt().coerceAtLeast(1)
        glyphs = CharArray(cols * rows) { '0' }
        ages = IntArray(cols * rows) { trail }
        heads = IntArray(cols) { -Random.nextInt(rows + 1) }
    }

    private fun resetGrid() {
        if (cols == 0) return
        ages.fill(trail)
        heads = IntArray(cols) { -Random.nextInt(rows + 1) }
        invalidate()
    }

    private fun tick() {
        if (cols == 0) return
        for (i in ages.indices) if (ages[i] < trail) ages[i]++
        for (c in 0 until cols) {
            heads[c]++
            val r = heads[c]
            if (r in 0 until rows) {
                val idx = r * cols + c
                glyphs[idx] = if (Random.nextBoolean()) '1' else '0'
                ages[idx] = 0
            } else if (r >= rows + trail / 2 && Random.nextInt(10) == 0) {
                heads[c] = -Random.nextInt(rows / 2 + 1)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val age = ages[idx]
                if (age >= trail) continue
                paint.alpha = if (age == 0) 170 else 110 * (trail - age) / trail
                canvas.drawText(glyphs, idx, 1, c * cellW + 2f, (r + 1) * cellH, paint)
            }
        }
    }
}