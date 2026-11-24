package com.nexus.cli

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.lifecycle.LifecycleOwner
import com.nexus.cli.nexusnet.WifiLabCard

/**
 * Adapter 4 halaman. Bertanggung jawab memanggil helper NexusUi
 * (makeCircular, animateProgress, spinVinyl, attachLongPress) agar
 * bug UI Bagian 1 benar-benar tereksekusi.
 */
class NexusPagerAdapter(private val actions: PageActions) :
    RecyclerView.Adapter<NexusPagerAdapter.PageHolder>() {

    private var mediaState: MediaState = MediaState()
    private var weather: WeatherState? = null
    private var widgets: Set<String> = QuickWidgets.default
    private var lightLux: Float? = null
    private var lastTerminalLine: String = ""

    fun setWidgets(newWidgets: Set<String>) {
        widgets = newWidgets
        notifyItemChanged(PAGE_QUICK)
    }

    fun setMedia(state: MediaState) {
        mediaState = state
        notifyItemChanged(PAGE_MEDIA)
    }

    fun setWeather(state: WeatherState?, note: String?) {
        weather = state
        notifyItemChanged(PAGE_MEDIA)
    }

    fun setLightLux(lux: Float?) {
        lightLux = lux
        notifyItemChanged(PAGE_METRICS)
    }

    fun setLastTerminalLine(line: String) {
        lastTerminalLine = line
    }

    fun pushMetrics(snapshot: MetricsSnapshot) = notifyItemChanged(PAGE_METRICS)

    fun tick() {
        if (itemCount > PAGE_MEDIA) notifyItemChanged(PAGE_MEDIA)
    }

    override fun getItemCount(): Int = 4

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = when (viewType) {
            PAGE_MEDIA -> inflater.inflate(R.layout.page_media_clock, parent, false)
            PAGE_QUICK -> inflater.inflate(R.layout.page_quick_launch, parent, false)
            PAGE_WORK -> inflater.inflate(R.layout.page_work_github, parent, false)
            else -> inflater.inflate(R.layout.page_metrics, parent, false)
        }
        return PageHolder(view, viewType)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        when (position) {
            PAGE_MEDIA -> bindMedia(holder.itemView)
            PAGE_QUICK -> bindQuick(holder.itemView)
            PAGE_WORK -> bindWork(holder.itemView)
            PAGE_METRICS -> bindMetrics(holder.itemView)
        }
    }

    // ---------------- Halaman 1: Media + Clock ----------------

    private fun bindMedia(root: View) {
        val vinylDisc = root.findViewById<View>(R.id.vinylDisc)
        val albumArt = root.findViewById<View>(R.id.albumArt)
        val btnPlayPause = root.findViewById<ImageButton>(R.id.btnPlayPause)
        val btnPrev = root.findViewById<ImageButton>(R.id.btnPrev)
        val btnNext = root.findViewById<ImageButton>(R.id.btnNext)
        val progress = root.findViewById<ProgressBar>(R.id.mediaProgress)
        val title = root.findViewById<TextView>(R.id.mediaTitle)
        val artist = root.findViewById<TextView>(R.id.mediaArtist)

        // BUG DISC: pastikan vinylDisc bulat
        NexusUi.makeCircular(vinylDisc)
        NexusUi.makeCircular(albumArt)

        // BUG TOMBOL PLAY: klik buka aplikasi HANYA di disc/album art
        vinylDisc.setOnClickListener { actions.onMediaCardClick() }
        albumArt.setOnClickListener { actions.onMediaCardClick() }

        // Tombol Play/Pause: play/pause di tempat
        btnPlayPause.setOnClickListener { actions.onMediaPlayPause() }
        btnPrev.setOnClickListener { actions.onMediaPrev() }
        btnNext.setOnClickListener { actions.onMediaNext() }

        title.text = mediaState.title.ifBlank { "—" }
        artist.text = mediaState.artist.ifBlank { "—" }

        // ANIMASI GARIS MEDIA
        NexusUi.animateProgress(
            progress,
            mediaState.isPlaying,
            mediaState.positionMs,
            mediaState.durationMs
        )
        NexusUi.spinVinyl(vinylDisc, mediaState.isPlaying)

        val icon = if (mediaState.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        btnPlayPause.setImageResource(icon)

        // Jam + cuaca
        val clock = root.findViewById<TextView>(R.id.clockBig)
        val date = root.findViewById<TextView>(R.id.dateText)
        val weatherNow = root.findViewById<TextView>(R.id.weatherNow)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        clock.text = java.time.LocalTime.now().format(fmt)
        date.text = java.time.LocalDate.now().toString()
        weatherNow.text = weather?.let { "Cuaca: ${it.label}" } ?: "Cuaca: —"
    }

    // ---------------- Halaman 2: Quick Launch ----------------

    private fun bindQuick(root: View) {
        val grid = root.findViewById<RecyclerView>(R.id.quickGrid)
        grid.layoutManager = GridLayoutManager(root.context, 4)
        grid.adapter = QuickTileAdapter(widgets.toList(), actions)
    }

    // ---------------- Halaman 3: Work + GitHub ----------------

    private fun bindWork(root: View) {
        // Placeholder: komponen sesi & GitHub sudah ditangani MainActivity/ lainnya.
    }

    // ---------------- Halaman 4: Metrics + WifiLabCard ----------------

    private fun bindMetrics(root: View) {
        val card = root.findViewById<WifiLabCard>(R.id.wifiLabCard)
        val owner = root.context as? LifecycleOwner
        if (card != null && owner != null) {
            card.attachTo(owner)
        }
    }

    class PageHolder(itemView: View, val page: Int) : RecyclerView.ViewHolder(itemView)

    companion object {
        const val PAGE_MEDIA = 0
        const val PAGE_QUICK = 1
        const val PAGE_WORK = 2
        const val PAGE_METRICS = 3
    }
}

/** Adapter kecil untuk grid Quick Launch. */
class QuickTileAdapter(
    private val ids: List<String>,
    private val actions: PageActions
) : RecyclerView.Adapter<QuickTileAdapter.TileHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_tile, parent, false)
        return TileHolder(view)
    }

    override fun getItemCount(): Int = ids.size

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val id = ids[position]
        holder.label.text = id.replace('_', ' ')
        holder.icon.setImageResource(iconFor(id))

        // Kustomisasi HANYA via long press 5000 ms (bukan swipe)
        NexusUi.attachLongPress(holder.itemView) { actions.onQuickLaunchLongPress() }
        holder.itemView.setOnClickListener {
            actions.onTileClick(QuickTile(id, holder.label.text.toString(), iconFor(id)))
        }
    }

    private fun iconFor(id: String): Int = when (id) {
        "terminal" -> android.R.drawable.ic_menu_edit
        "browser" -> android.R.drawable.ic_menu_view
        "files" -> android.R.drawable.ic_menu_manage
        "camera" -> android.R.drawable.ic_menu_camera
        "calc" -> android.R.drawable.ic_menu_sort_by_size
        "clock" -> android.R.drawable.ic_menu_recent_history
        "settings" -> android.R.drawable.ic_menu_preferences
        "nexus_net" -> com.nexus.cli.R.drawable.ic_nexus_net
        else -> android.R.drawable.ic_menu_compass
    }

    class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tileIcon)
        val label: TextView = view.findViewById(R.id.tileLabel)
    }
}