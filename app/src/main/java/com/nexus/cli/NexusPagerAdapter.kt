package com.nexus.cli

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.nexus.cli.databinding.ItemForecastDayBinding
import com.nexus.cli.databinding.ItemQuickTileBinding
import com.nexus.cli.databinding.PageMediaClockBinding
import com.nexus.cli.databinding.PageMetricsBinding
import com.nexus.cli.databinding.PageQuickLaunchBinding
import com.nexus.cli.databinding.PageWorkGithubBinding
import com.nexus.cli.nexusnet.WifiLabCard
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Objects
import kotlin.math.max
import kotlin.math.roundToInt

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

    /** Naik tiap kali ukuran/jenis widget berubah, agar Halaman 2 dibangun ulang walau set-nya sama. */
    private var widgetsRevision = 0

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
        (holder as? MediaClockHolder)?.release()
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

    fun setWidgets(widgets: Set<String>) {
        state = state.copy(widgets = widgets)
        renderAll()
    }

    /** Paksa Halaman 2 membangun ulang widget (dipakai setelah ukuran widget disimpan). */
    fun reloadWidgets() {
        widgetsRevision++
        renderAll()
    }

    fun setLightLux(lux: Float?) {
        state = state.copy(lightLux = lux)
        renderAll()
    }

    fun setLastTerminalLine(line: String) {
        state = state.copy(lastTerminalLine = line)
        renderAll()
    }

    /** Tambah titik sparkline + simpan snapshot. Teks dirender oleh [tick]. */
    fun pushMetrics(snapshot: MetricsSnapshot) {
        state = state.copy(metrics = snapshot)
        holders.filterIsInstance<MetricsHolder>().forEach { it.pushSparks(snapshot) }
        holders.filterIsInstance<QuickLaunchHolder>().forEach { it.pushCpu(snapshot.cpuPct) }
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
        private var shownArt: Bitmap? = null

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

    // ---------- Halaman 2: Quick Launch 4x3 + widget ----------

    inner class QuickLaunchHolder(private val b: PageQuickLaunchBinding) : PageHolder(b.root) {

        private var shownWidgets: Set<String>? = null
        private var shownRevision = -1
        private var builtWidth = 0
        private val updaters = ArrayList<(UiState) -> Unit>()
        private var cpuSpark: SparklineView? = null

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

            // Tekan lama 5000 ms (batal jika jari bergeser/swipe) -> BottomSheet "Kustomisasi Widget".
            b.root.longPressMs = NexusUi.QUICK_LONG_PRESS_MS
            b.root.onLongPress = { actions.onQuickLaunchLongPress() }

            // Lebar sel widget bergantung lebar halaman: bangun ulang saat lebar sudah/ berubah.
            b.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val w = right - left
                if (w > 0 && w != builtWidth && shownWidgets != null) {
                    b.root.post { shownWidgets?.let { rebuildWidgets(it) } }
                }
            }
        }

        fun pushCpu(value: Float) {
            cpuSpark?.addValue(value)
        }

        override fun render(state: UiState) {
            if (state.widgets != shownWidgets || shownRevision != widgetsRevision) {
                shownWidgets = state.widgets
                shownRevision = widgetsRevision
                rebuildWidgets(state.widgets)
            }
            updaters.forEach { it(state) }
        }

        /**
         * Widget ditata di GridLayout 4 kolom. Ukuran (1x1, 2x1, 1x2, 2x2, 4x1) dari QuickWidgetStore
         * diterapkan sebagai SPAN kolom/baris + lebar/tinggi piksel yang pasti (tanpa weight,
         * karena weight GridLayout tidak andal untuk sel yang span).
         */
        private fun rebuildWidgets(selected: Set<String>) {
            val ctx = b.root.context
            updaters.clear()
            cpuSpark = null
            b.widgetContainer.removeAllViews()

            val keys = QuickWidgets.all.keys.filter { it in selected }
            b.widgetContainer.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
            if (keys.isEmpty()) return

            val columns = b.widgetContainer.columnCount
            val margin = dp(ctx, 4)
            val unitH = dp(ctx, WIDGET_UNIT_DP)

            var available = b.root.width - b.root.paddingLeft - b.root.paddingRight
            if (available <= 0) available = ctx.resources.displayMetrics.widthPixels - 2 * dp(ctx, 12)
            builtWidth = b.root.width
            val cellW = (available - columns * 2 * margin) / columns

            keys.forEach { key ->
                val size = QuickWidgetStore.sizeOf(ctx, key)
                val cols = size.cols.coerceIn(1, columns)
                val rows = size.rows.coerceAtLeast(1)

                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, rows),
                    GridLayout.spec(GridLayout.UNDEFINED, cols)
                ).apply {
                    width = cols * cellW + (cols - 1) * 2 * margin
                    height = rows * unitH + (rows - 1) * 2 * margin
                    setMargins(margin, margin, margin, margin)
                }

                val card = buildWidget(ctx, key)
                // Long press 5 detik per widget; key disimpan agar BottomSheet langsung memilih widget ini.
                NexusUi.attachLongPress(
                    card,
                    NexusUi.QUICK_LONG_PRESS_MS,
                    onDown = { NexusUi.pressedWidget = key }
                ) { actions.onQuickLaunchLongPress() }
                b.widgetContainer.addView(card, lp)
            }
            b.widgetContainer.requestLayout()
        }

        private fun buildWidget(ctx: Context, key: String): View {
            val green = ContextCompat.getColor(ctx, R.color.nexus_green)
            val white = ContextCompat.getColor(ctx, R.color.text_primary)
            val gray = ContextCompat.getColor(ctx, R.color.text_secondary)

            val card = MaterialCardView(ctx).apply {
                radius = dp(ctx, 16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.glass_fill))
                strokeColor = ContextCompat.getColor(ctx, R.color.glass_stroke)
                strokeWidth = dp(ctx, 1)
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            }
            card.addView(
                inner,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            fun tv(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
                textSize = sp
                setTextColor(color)
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            fun label(t: String) = tv(9f, gray).apply {
                text = t
                letterSpacing = 0.15f
            }

            when (key) {
                "clock_big" -> {
                    val t = tv(30f, white, true)
                    inner.addView(label("JAM"))
                    inner.addView(t)
                    updaters += { s -> t.text = fmtTime(s.nowMillis, HM_FMT) }
                }
                "calendar" -> {
                    val day = tv(13f, green, true)
                    val date = tv(12f, white)
                    inner.addView(label("KALENDER"))
                    inner.addView(day)
                    inner.addView(date)
                    updaters += { s ->
                        day.text = fmtTime(s.nowMillis, DAY_FMT)
                        date.text = fmtTime(s.nowMillis, SHORT_DATE_FMT)
                    }
                }
                "weather" -> {
                    val t = tv(13f, white)
                    inner.addView(label("CUACA"))
                    inner.addView(t)
                    updaters += { s ->
                        val w = s.weather
                        t.text = if (w != null) "${w.tempC}°C\n${w.condition}" else "—"
                    }
                }
                "music_player" -> {
                    val title = tv(12f, white, true)
                    val artist = tv(10f, gray)
                    inner.addView(label("MUSIK"))
                    inner.addView(title)
                    inner.addView(artist)
                    card.isClickable = true
                    card.setOnClickListener { actions.onMediaPlayPause() }
                    updaters += { s ->
                        val m = s.media
                        title.text = if (m.hasSession) m.title else "Tidak ada media"
                        artist.text = if (m.hasSession) {
                            "${if (m.isPlaying) "▶" else "⏸"} ${m.artist}"
                        } else {
                            "Ketuk untuk putar"
                        }
                    }
                }
                "cpu_chart" -> {
                    inner.addView(label("CPU"))
                    val spark = SparklineView(ctx).apply {
                        maxPoints = 30
                        fixedMax = 100f
                        lineColor = green
                    }
                    cpuSpark = spark
                    inner.addView(spark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                }
                "terminal_mini" -> {
                    val t = tv(11f, green)
                    inner.addView(label("TERMINAL"))
                    inner.addView(t)
                    card.isClickable = true
                    card.setOnClickListener {
                        actions.onTileClick(QuickTiles.all.first { it.id == "terminal" })
                    }
                    updaters += { s -> t.text = "> ${s.lastTerminalLine.ifBlank { "_" }}" }
                }
                "light_sensor" -> {
                    val t = tv(20f, green, true)
                    inner.addView(label("CAHAYA"))
                    inner.addView(t)
                    updaters += { s ->
                        t.text = s.lightLux?.let { "${it.roundToInt()} lx" } ?: "— lx"
                    }
                }
            }
            return card
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

            // Kartu NEXUS-NET (WifiLabCard) tepat di bawah mini strip, tanpa mengubah page_metrics.xml.
            val card = WifiLabCard(ctx)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
            b.root.addView(card, 1, lp)
            (ctx as? LifecycleOwner)?.let { card.attachTo(it) }
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
        /** Tinggi satu baris widget (dp). Widget 2x2 = 2 baris. */
        const val WIDGET_UNIT_DP = 76

        val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.forLanguageTag("id-ID"))
        val DAY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("id-ID"))
        val SHORT_DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("id-ID"))

        fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

        fun fmtTime(millis: Long, fmt: DateTimeFormatter): String =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(fmt)

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