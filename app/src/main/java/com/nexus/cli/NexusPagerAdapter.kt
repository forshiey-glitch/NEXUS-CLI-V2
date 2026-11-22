package com.nexus.cli

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nexus.cli.databinding.ItemForecastDayBinding
import com.nexus.cli.databinding.ItemQuickTileBinding
import com.nexus.cli.databinding.PageMediaClockBinding
import com.nexus.cli.databinding.PageMetricsBinding
import com.nexus.cli.databinding.PageQuickLaunchBinding
import com.nexus.cli.databinding.PageWorkGithubBinding
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** SATU-SATUNYA adapter untuk ViewPager2 (4 halaman). */
class NexusPagerAdapter(private val actions: PageActions) :
    RecyclerView.Adapter<NexusPagerAdapter.PageHolder>() {

    abstract class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun render(state: UiState)
    }

    private var state = UiState()
    private val holders = mutableSetOf<PageHolder>()

    private var sessionRunning = false
    private var sessionStartedAt = 0L
    private var sessionAccumMs = 0L
    private var sessionFinishedMs = 0L

    // ---------- Adapter ----------

    override fun getItemCount(): Int = 4

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> MediaClockHolder(PageMediaClockBinding.inflate(inflater, parent, false))
            1 -> QuickLaunchHolder(PageQuickLaunchBinding.inflate(inflater, parent, false))
            2 -> WorkGithubHolder(PageWorkGithubBinding.inflate(inflater, parent, false))
            else -> MetricsHolder(PageMetricsBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holders.add(holder)
        holder.render(state)
    }

    override fun onViewRecycled(holder: PageHolder) {
        holders.remove(holder)
        super.onViewRecycled(holder)
    }

    // ---------- Update dari luar ----------

    fun setMedia(media: MediaState) {
        state = state.copy(media = media)
        renderAll()
    }

    fun setWeather(weather: WeatherInfo?, error: String?) {
        state = state.copy(weather = weather, weatherError = error)
        renderAll()
    }

    /** Tambah titik sparkline + simpan snapshot. Teks dirender oleh [tick]. */
    fun pushMetrics(snapshot: MetricsSnapshot) {
        state = state.copy(metrics = snapshot)
        holders.filterIsInstance<MetricsHolder>().forEach { it.pushSparks(snapshot) }
    }

    /** Dipanggil tiap detik: jam, progress lagu, timer sesi. */
    fun tick() {
        val elapsed = currentSessionElapsed()
        state = state.copy(
            nowMillis = System.currentTimeMillis(),
            sessionRunning = sessionRunning,
            sessionElapsedMs = elapsed,
            sessionTotalTodayMs = sessionFinishedMs + elapsed
        )
        renderAll()
    }

    private fun renderAll() {
        holders.forEach { it.render(state) }
    }

    // ---------- Sesi kerja ----------

    private fun currentSessionElapsed(): Long =
        if (sessionRunning) sessionAccumMs + (SystemClock.elapsedRealtime() - sessionStartedAt)
        else sessionAccumMs

    private fun toggleSession() {
        if (sessionRunning) {
            sessionAccumMs += SystemClock.elapsedRealtime() - sessionStartedAt
            sessionRunning = false
        } else {
            sessionStartedAt = SystemClock.elapsedRealtime()
            sessionRunning = true
        }
        tick()
    }

    private fun resetSession() {
        sessionFinishedMs += currentSessionElapsed()
        sessionAccumMs = 0L
        sessionRunning = false
        tick()
    }

    // ---------- Halaman 1: Media + Jam/Cuaca ----------

    inner class MediaClockHolder(private val b: PageMediaClockBinding) : PageHolder(b.root) {

        private var shownWeather: WeatherInfo? = null

        init {
            b.btnPrev.setOnClickListener { actions.onMediaPrev() }
            b.btnPlayPause.setOnClickListener { actions.onMediaPlayPause() }
            b.btnNext.setOnClickListener { actions.onMediaNext() }
            b.mediaCard.setOnClickListener { actions.onMediaCardClick() }
        }

        override fun render(state: UiState) {
            val m = state.media

            b.mediaSourceLabel.text = if (m.hasSession) m.source else "MEDIA"
            b.trackTitle.text = when {
                m.hasSession -> m.title
                !m.hasAccess -> "Izin notifikasi dibutuhkan"
                else -> "Tidak ada media"
            }
            b.trackArtist.text = when {
                m.hasSession -> m.artist
                !m.hasAccess -> "Ketuk kartu ini untuk memberi akses"
                else -> "Putar lagu di Spotify / YouTube Music"
            }
            b.btnPlayPause.setImageResource(if (m.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

            val pos = estimatedPosition(m)
            b.mediaProgress.progress =
                if (m.durationMs > 0) (pos * 1000 / m.durationMs).toInt().coerceIn(0, 1000) else 0
            b.mediaTime.text = "${fmtMs(pos)} / ${fmtMs(m.durationMs)}"

            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(state.nowMillis), ZoneId.systemDefault())
            b.clockText.text = dt.format(HM_FMT)
            b.dateText.text = dt.format(DATE_FMT)

            val w = state.weather
            b.weatherCity.text = (w?.city ?: WeatherRepository.DEFAULT_CITY).uppercase()
            val err = state.weatherError
            b.weatherText.text = when {
                w != null -> "${w.condition} · ${w.tempC}°C · ${w.windKmh} km/h"
                err != null -> err
                else -> "Memuat cuaca…"
            }

            if (w !== shownWeather) {
                shownWeather = w
                rebuildForecast(w)
            }
        }

        private fun rebuildForecast(w: WeatherInfo?) {
            b.forecastContainer.removeAllViews()
            if (w == null) return
            val inflater = LayoutInflater.from(b.root.context)
            w.daily.forEach { day ->
                val item = ItemForecastDayBinding.inflate(inflater, b.forecastContainer, false)
                item.dayLabel.text = day.label
                item.dayCondition.text = day.condition
                item.dayTemps.text = "${day.minC}°/${day.maxC}°"
                b.forecastContainer.addView(item.root)
            }
        }
    }

    // ---------- Halaman 2: Quick Launch 4x3 ----------

    inner class QuickLaunchHolder(private val b: PageQuickLaunchBinding) : PageHolder(b.root) {

        init {
            val inflater = LayoutInflater.from(b.root.context)
            val margin = (6 * b.root.resources.displayMetrics.density).toInt()

            QuickTiles.all.forEachIndexed { index, tile ->
                val tb = ItemQuickTileBinding.inflate(inflater, b.quickGrid, false)
                tb.tileIcon.text = tile.glyph
                tb.tileLabel.text = tile.label
                tb.root.setOnClickListener { actions.onTileClick(tile) }

                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(index / 4, GridLayout.FILL, 1f),
                    GridLayout.spec(index % 4, GridLayout.FILL, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(margin, margin, margin, margin)
                }
                b.quickGrid.addView(tb.root, lp)
            }
        }

        override fun render(state: UiState) {
            // statis
        }
    }

    // ---------- Halaman 3: Sesi Kerja + GitHub ----------

    inner class WorkGithubHolder(private val b: PageWorkGithubBinding) : PageHolder(b.root) {

        init {
            b.btnSessionToggle.setOnClickListener { toggleSession() }
            b.btnSessionReset.setOnClickListener { resetSession() }
        }

        override fun render(state: UiState) {
            b.sessionTimer.text = fmtHms(state.sessionElapsedMs)
            b.sessionState.text = when {
                state.sessionRunning -> "RUNNING"
                state.sessionElapsedMs > 0 -> "PAUSED"
                else -> "IDLE"
            }
            b.btnSessionToggle.text = when {
                state.sessionRunning -> "PAUSE"
                state.sessionElapsedMs > 0 -> "RESUME"
                else -> "START"
            }
            b.sessionTotalToday.text = "Total fokus hari ini: ${fmtHms(state.sessionTotalTodayMs)}"
        }
    }

    // ---------- Halaman 4: Hardware Metrics ----------

    inner class MetricsHolder(private val b: PageMetricsBinding) : PageHolder(b.root) {

        init {
            val ctx = b.root.context
            b.gpuSpark.lineColor = ContextCompat.getColor(ctx, R.color.nexus_amber)
            b.netSpark.lineColor = ContextCompat.getColor(ctx, R.color.nexus_cyan)
            b.netSpark.fixedMax = null
        }

        fun pushSparks(s: MetricsSnapshot) {
            b.cpuSpark.addValue(s.cpuPct)
            b.gpuSpark.addValue(max(s.gpuPct, 0f))
            b.ramSpark.addValue(s.ramPct)
            b.netSpark.addValue(s.rxKbps + s.txKbps)
        }

        override fun render(state: UiState) {
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(state.nowMillis), ZoneId.systemDefault())
            b.miniClock.text = dt.format(HM_FMT)

            val w = state.weather
            b.miniWeather.text = if (w != null) "${w.condition} ${w.tempC}°C" else "—"

            val m = state.media
            b.miniMedia.text = if (m.hasSession) {
                "${if (m.isPlaying) "[PLAY]" else "[PAUSE]"} ${m.title} — ${m.artist}"
            } else {
                "Tidak ada media"
            }

            val s = state.metrics ?: return
            b.cpuValue.text = "${s.cpuPct.roundToInt()}%"
            b.cpuDetail.text = "${s.cores} cores"

            if (s.gpuPct >= 0f) {
                b.gpuValue.text = "${s.gpuPct.roundToInt()}%"
                b.gpuDetail.text = "load"
            } else {
                b.gpuValue.text = "N/A"
                b.gpuDetail.text = "sensor tidak tersedia"
            }

            b.ramValue.text = "${s.ramPct.roundToInt()}%"
            b.ramDetail.text = "${s.ramUsedMb} / ${s.ramTotalMb} MB"

            b.netValue.text = s.netLabel
            b.netDetail.text = "↓ ${fmtKbps(s.rxKbps)}  ↑ ${fmtKbps(s.txKbps)}"
        }
    }

    // ---------- helper ----------

    private companion object {
        val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.forLanguageTag("id-ID"))

        fun estimatedPosition(m: MediaState): Long {
            if (!m.hasSession) return 0L
            val base = if (m.isPlaying) {
                m.positionMs + ((SystemClock.elapsedRealtime() - m.updatedAtMs) * m.speed).toLong()
            } else {
                m.positionMs
            }
            return if (m.durationMs > 0) base.coerceIn(0L, m.durationMs) else base.coerceAtLeast(0L)
        }

        fun fmtMs(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0L)
            return "%d:%02d".format(s / 60, s % 60)
        }

        fun fmtHms(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0L)
            return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        }

        fun fmtKbps(kbps: Float): String =
            if (kbps >= 1024f) "%.1f MB/s".format(kbps / 1024f) else "%.0f KB/s".format(kbps)
    }
}