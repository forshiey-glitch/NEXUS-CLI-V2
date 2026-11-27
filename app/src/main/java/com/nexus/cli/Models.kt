package com.nexus.cli

import android.graphics.Bitmap
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nexus.cli.nexusnet.WifiSnapshot
import com.nexus.cli.nexusnet.WifiTarget

enum class LineType { INPUT, OUTPUT, ERROR, SYSTEM }

data class TerminalLine(
    val text: String,
    val type: LineType = LineType.OUTPUT
)

data class ShellResult(
    val lines: List<TerminalLine> = emptyList(),
    val clear: Boolean = false,
    val exit: Boolean = false
) {
    companion object {
        fun out(vararg text: String): ShellResult =
            ShellResult(text.map { TerminalLine(it, LineType.OUTPUT) })

        fun out(lines: List<String>): ShellResult =
            ShellResult(lines.map { TerminalLine(it, LineType.OUTPUT) })

        fun error(text: String): ShellResult =
            ShellResult(listOf(TerminalLine(text, LineType.ERROR)))
    }
}

data class MediaState(
    val title: String = "Tidak ada media",
    val artist: String = "—",
    val isPlaying: Boolean = false,
    val source: String = "MEDIA",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val speed: Float = 1f,
    val hasAccess: Boolean = false,
    val hasSession: Boolean = false,
    val albumArt: Bitmap? = null
)

data class DayForecast(
    val label: String,
    val condition: String,
    val maxC: Int,
    val minC: Int
)

data class WeatherInfo(
    val city: String,
    val tempC: Int,
    val condition: String,
    val windKmh: Int,
    val daily: List<DayForecast>
)

data class MetricsSnapshot(
    val cpuPct: Float,
    val gpuPct: Float,
    val ramPct: Float,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val batteryPct: Int,
    val charging: Boolean,
    val netLabel: String,
    val rxKbps: Float,
    val txKbps: Float,
    val cores: Int
)

data class UiState(
    val media: MediaState = MediaState(),
    val weather: WeatherInfo? = null,
    val weatherError: String? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val sessionRunning: Boolean = false,
    val sessionElapsedMs: Long = 0L,
    val sessionTotalTodayMs: Long = 0L,
    val wifi: WifiSnapshot = WifiSnapshot()
)

/** View-view terminal di Halaman 2; diserahkan adapter ke MainActivity saat halaman dibuat. */
class TerminalViews(
    val recycler: RecyclerView,
    val prompt: TextView,
    val input: EditText
)

interface PageActions {
    fun onMediaPrev()
    fun onMediaPlayPause()
    fun onMediaNext()
    fun onMediaCardClick()

    /** Jaringan di Nexus-Net Panel diketuk (hanya memilih target; password diketik manual di terminal). */
    fun onWifiSelected(target: WifiTarget)

    fun onTerminalReady(views: TerminalViews)
    fun onTerminalClose()
}
