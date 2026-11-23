package com.nexus.cli

import android.graphics.Bitmap

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

data class AppInfo(
    val label: String,
    val packageName: String
)

data class QuickTile(
    val id: String,
    val glyph: String,
    val label: String
)

object QuickTiles {
    val all: List<QuickTile> = listOf(
        QuickTile("terminal", ">_", "Terminal"),
        QuickTile("vscode", "{}", "VS Code"),
        QuickTile("browser", "www", "Browser"),
        QuickTile("files", "DIR", "Files"),
        QuickTile("spotify", "SPT", "Spotify"),
        QuickTile("discord", "DSC", "Discord"),
        QuickTile("ytmusic", "YTM", "YT Music"),
        QuickTile("camera", "CAM", "Camera"),
        QuickTile("calc", "123", "Calculator"),
        QuickTile("clock", "CLK", "Clock"),
        QuickTile("mute", "MUT", "Mute"),
        QuickTile("settings", "SET", "Settings")
    )
}

/** Widget yang bisa dipasang di halaman Quick Launch (urutan = urutan tampil). */
object QuickWidgets {
    val all: LinkedHashMap<String, String> = linkedMapOf(
        "clock_big" to "Jam Besar",
        "calendar" to "Kalender",
        "weather" to "Cuaca",
        "music_player" to "Pemutar Musik",
        "cpu_chart" to "Grafik CPU",
        "terminal_mini" to "Terminal Mini",
        "light_sensor" to "Sensor Cahaya"
    )

    val default: Set<String> = setOf("clock_big", "music_player")
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
    val metrics: MetricsSnapshot? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val sessionRunning: Boolean = false,
    val sessionElapsedMs: Long = 0L,
    val sessionTotalTodayMs: Long = 0L,
    val widgets: Set<String> = QuickWidgets.default,
    val lightLux: Float? = null,
    val lastTerminalLine: String = ""
)

interface PageActions {
    fun onTileClick(tile: QuickTile)
    fun onMediaPrev()
    fun onMediaPlayPause()
    fun onMediaNext()
    fun onMediaCardClick()
    fun onQuickLaunchLongPress()
}