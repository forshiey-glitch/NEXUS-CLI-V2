package com.nexus.cli

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.nexus.cli.databinding.ItemForecastDayBinding
import com.nexus.cli.databinding.PageDashboardBinding
import com.nexus.cli.databinding.PageTerminalBinding
import com.nexus.cli.nexusnet.WifiSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Objects

/** Animasi tombol: mengecil ke 0.9 saat ditekan lalu kembali 1.0 (100ms, ViewPropertyAnimator). */
@SuppressLint("ClickableViewAccessibility")
private fun View.attachPressAnim() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
        }
        false
    }
}

/**
 * SATU-SATUNYA adapter untuk ViewPager2 (2 halaman):
 * 0 = Bento Dashboard (Media + Jam/Cuaca + Nexus-Net Panel), 1 = Terminal / Command Center.
 */
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

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            PAGE_DASHBOARD -> DashboardHolder(PageDashboardBinding.inflate(inflater, parent, false))
            else -> TerminalHolder(PageTerminalBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holders.add(holder)
        holder.render(state)
    }

    override fun onViewRecycled(holder: PageHolder) {
        holders.remove(holder)
        (holder as? DashboardHolder)?.release()
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

    /** Hasil polling Wi-Fi (dipanggil MainActivity tiap ~1 detik). */
    fun setWifi(wifi: WifiSnapshot) {
        state = state.copy(wifi = wifi)
        holders.filterIsInstance<DashboardHolder>().forEach { it.renderWifi(wifi) }
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

    // ---------- Halaman 1: Bento Dashboard ----------

    inner class DashboardHolder(private val b: PageDashboardBinding) : PageHolder(b.root) {

        private var shownWeather: WeatherInfo? = null
        private var shownArt: Bitmap? = null
        private var shownWifi: WifiSnapshot? = null

        // Hanya piringan yang berputar; albumArt tetap diam di tengah.
        private val vinylAnimator: ObjectAnimator =
            ObjectAnimator.ofFloat(b.vinylDisc, View.ROTATION, 0f, 360f).apply {
                duration = 6000L
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
            }

        private var lastProgressKey: Int? = null

        init {
            // Piringan: muat ulang gambar + bulat tanpa menimpa outline ShapeableImageView.
            NexusUi.prepareVinyl(b.vinylDisc, R.drawable.ic_vinyl_disc)
            NexusUi.applyCircle(b.albumArt)

            b.btnPrev.setOnClickListener { actions.onMediaPrev() }
            // Play/Pause HANYA play/pause di tempat (tidak membuka aplikasi musik).
            b.btnPlayPause.setOnClickListener { actions.onMediaPlayPause() }
            b.btnNext.setOnClickListener { actions.onMediaNext() }

            // Buka aplikasi musik HANYA dari piringan / sampul album.
            val openMusic = View.OnClickListener { actions.onMediaCardClick() }
            b.vinylDisc.setOnClickListener(openMusic)
            b.albumArt.setOnClickListener(openMusic)

            b.btnPrev.attachPressAnim()
            b.btnPlayPause.attachPressAnim()
            b.btnNext.attachPressAnim()

            b.nexusNetPanel.onSelect = { target -> actions.onWifiSelected(target) }
        }

        fun release() {
            vinylAnimator.cancel()
            NexusUi.cancelProgress(b.mediaProgress)
        }

        private fun spin(on: Boolean) {
            if (on) {
                if (!vinylAnimator.isStarted) vinylAnimator.start()
                else if (vinylAnimator.isPaused) vinylAnimator.resume()
            } else if (vinylAnimator.isRunning) {
                vinylAnimator.pause()
            }
        }

        fun renderWifi(wifi: WifiSnapshot) {
            if (wifi === shownWifi) return
            shownWifi = wifi
            b.nexusNetPanel.render(wifi)
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
                !m.hasAccess -> "Ketuk piringan atau sampul untuk memberi akses"
                else -> "Putar lagu di Spotify / YouTube Music"
            }
            b.btnPlayPause.setImageResource(if (m.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

            if (m.albumArt !== shownArt) {
                shownArt = m.albumArt
                if (m.albumArt != null) b.albumArt.setImageBitmap(m.albumArt)
                else b.albumArt.setImageResource(R.drawable.ic_album_default)
            }
            spin(m.isPlaying)

            val pos = estimatedPosition(m)
            // ObjectAnimator linear: hanya dimulai ulang bila state media berubah (bukan tiap detik).
            val progressKey = Objects.hash(
                m.hasSession, m.isPlaying, m.durationMs, m.positionMs, m.updatedAtMs, m.speed, m.title
            )
            if (progressKey != lastProgressKey) {
                lastProgressKey = progressKey
                NexusUi.animateProgress(
                    b.mediaProgress, m.hasSession && m.isPlaying, pos, m.durationMs, m.speed
                )
            }
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

            renderWifi(state.wifi)
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

    // ---------- Halaman 2: Terminal / Command Center ----------

    inner class TerminalHolder(private val b: PageTerminalBinding) : PageHolder(b.root) {

        init {
            b.btnSessionToggle.setOnClickListener { toggleSession() }
            b.btnSessionReset.setOnClickListener { resetSession() }
            b.btnCloseTerminal.setOnClickListener { actions.onTerminalClose() }
            actions.onTerminalReady(TerminalViews(b.terminalRecycler, b.terminalPrompt, b.terminalInput))
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

    // ---------- helper ----------

    companion object {
        const val PAGE_DASHBOARD = 0
        const val PAGE_TERMINAL = 1
        const val PAGE_COUNT = 2

        private val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.forLanguageTag("id-ID"))

        private fun estimatedPosition(m: MediaState): Long {
            if (!m.hasSession) return 0L
            val base = if (m.isPlaying) {
                m.positionMs + ((SystemClock.elapsedRealtime() - m.updatedAtMs) * m.speed).toLong()
            } else {
                m.positionMs
            }
            return if (m.durationMs > 0) base.coerceIn(0L, m.durationMs) else base.coerceAtLeast(0L)
        }

        private fun fmtMs(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0L)
            return "%d:%02d".format(s / 60, s % 60)
        }

        private fun fmtHms(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0L)
            return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        }
    }
}
