package com.nexus.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * @param cpuProvider mengembalikan penggunaan CPU 0..100 dari CpuMonitor yang sudah ada.
 */
class ChartCommand(private val cpuProvider: () -> Float) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = mutableListOf<Float>()
    private var job: Job? = null

    fun execute(args: List<String>, output: (String) -> Unit) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "cpu" -> chartCpu(output)
            else -> output("Usage: chart cpu")
        }
    }

    private fun chartCpu(output: (String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            output("[chart] mengambil data CPU...")
            do {
                val value = withContext(Dispatchers.Default) { cpuProvider() }
                record(value)
                if (history.size < MAX_POINTS) delay(SAMPLE_DELAY_MS)
            } while (history.size < MAX_POINTS)
            output(render(history.toList()))
        }
    }

    private fun record(value: Float) {
        history.add(value.coerceIn(0f, 100f))
        while (history.size > MAX_POINTS) history.removeAt(0)
    }

    private fun render(data: List<Float>): String {
        val sb = StringBuilder()
        sb.append("CPU USAGE (last ${data.size} samples)\n")

        for (row in 0 until ROWS) {
            val fromBottom = ROWS - 1 - row
            val label = when (row) {
                0 -> "100%"
                ROWS / 2 -> " 50%"
                else -> "    "
            }
            sb.append(label).append(" │")
            for (v in data) {
                val totalEighths = Math.round(v / 100f * ROWS * 8)
                val fill = (totalEighths - fromBottom * 8).coerceIn(0, 8)
                val ch = BLOCKS[fill]
                sb.append(' ').append(ch).append(ch)
            }
            sb.append('\n')
        }

        sb.append("  0% └").append("───".repeat(data.size)).append('\n')
        sb.append("      ")
        data.forEach { sb.append(String.format(Locale.US, "%3d", Math.round(it))) }
        sb.append('\n')
        sb.append("Skala: ▁▂▃▄▅▆▇█")
        return sb.toString()
    }

    fun close() {
        job?.cancel()
        scope.cancel()
    }

    companion object {
        private const val MAX_POINTS = 10
        private const val ROWS = 6
        private const val SAMPLE_DELAY_MS = 300L
        private val BLOCKS = charArrayOf(' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
    }
}