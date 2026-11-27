package com.nexus.cli.nexusnet

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexus.cli.R
import com.nexus.cli.databinding.ItemWifiNetworkBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nexus-Net Panel (pengganti GitHub Card) di Bento Dashboard: daftar Wi-Fi (SSID + sinyal)
 * yang di-refresh MainActivity tiap ~1 detik lewat [render]. Hanya SCAN/daftar.
 * Mengetuk baris = memilih target ([onSelect]); password diketik manual di terminal.
 */
class NexusNetPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private data class Row(val target: WifiTarget, val selected: Boolean)

    private val tvStatus: TextView
    private val tvEmpty: TextView
    private val tvLive: TextView
    private val list: RecyclerView

    private val adapter = WifiAdapter { t ->
        selectedBssid = t.bssid
        submit(lastNetworks)
        onSelect?.invoke(t)
    }

    private var lastNetworks: List<WifiTarget> = emptyList()
    private var selectedBssid: String? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    var onSelect: ((WifiTarget) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_nexus_net_panel, this, true)
        tvStatus = findViewById(R.id.wifiStatus)
        tvEmpty = findViewById(R.id.wifiEmpty)
        tvLive = findViewById(R.id.wifiLive)
        list = findViewById(R.id.wifiList)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.itemAnimator = null
    }

    fun render(snapshot: WifiSnapshot) {
        lastNetworks = snapshot.networks
        submit(snapshot.networks)

        val time = if (snapshot.updatedAtMs > 0L) timeFmt.format(Date(snapshot.updatedAtMs)) else "--:--:--"
        val age = if (snapshot.dataAgeSec >= 0L) " · data ${snapshot.dataAgeSec}s lalu" else ""
        tvStatus.text = when {
            snapshot.networks.isNotEmpty() -> "${snapshot.networks.size} jaringan · $time$age"
            snapshot.note != null -> snapshot.note
            else -> "Memindai…"
        }
        tvEmpty.text = snapshot.note ?: "Memindai…"
        tvEmpty.visibility = if (snapshot.networks.isEmpty()) View.VISIBLE else View.GONE
        tvLive.alpha = if (snapshot.networks.isEmpty()) 0.4f else 1f
    }

    private fun submit(networks: List<WifiTarget>) {
        adapter.submitList(networks.map { Row(it, it.bssid == selectedBssid) })
    }

    private class WifiAdapter(private val onClick: (WifiTarget) -> Unit) :
        ListAdapter<Row, WifiAdapter.Holder>(Diff) {

        class Holder(val b: ItemWifiNetworkBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemWifiNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            val t = row.target
            val ctx = holder.itemView.context
            val green = ContextCompat.getColor(ctx, R.color.nexus_green)
            val gray = ContextCompat.getColor(ctx, R.color.glass_stroke)

            holder.b.wifiSsid.text = NexusNetPresenter.label(t)
            holder.b.wifiSsid.setTypeface(
                Typeface.DEFAULT, if (row.selected) Typeface.BOLD else Typeface.NORMAL
            )
            holder.b.wifiSsid.setTextColor(
                if (row.selected) green else ContextCompat.getColor(ctx, R.color.text_primary)
            )
            holder.b.wifiMeta.text = "${t.encryption} · CH ${t.channel} · ${t.signalDbm} dBm"

            val level = signalLevel(t.signalDbm)
            holder.b.wifiBars.text = SpannableString(BARS).apply {
                setSpan(ForegroundColorSpan(green), 0, level, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(gray), level, BARS.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            holder.itemView.setBackgroundColor(
                if (row.selected) ContextCompat.getColor(ctx, R.color.nexus_green_soft) else 0
            )
            holder.itemView.setOnClickListener { onClick(t) }
        }

        private object Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.target.bssid == b.target.bssid
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        private companion object {
            const val BARS = "▂▄▆█"

            fun signalLevel(dbm: Int): Int = when {
                dbm >= -55 -> 4
                dbm >= -65 -> 3
                dbm >= -75 -> 2
                dbm >= -85 -> 1
                else -> 0
            }
        }
    }
}
