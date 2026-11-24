package com.nexus.cli.nexusnet

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nexus.cli.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Kartu Glassmorphism Halaman 4.
 * attachTo(LifecycleOwner) dipanggil NexusPagerAdapter.bindMetrics.
 */
class WifiLabCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val tvStatus: TextView
    private val tvTarget: TextView
    private val tvConnect: TextView
    private val progress: LinearProgressIndicator
    private var attached = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_wifi_lab_card, this, true)
        tvStatus = findViewById(R.id.tvScanStatus)
        tvTarget = findViewById(R.id.tvSelectedTarget)
        tvConnect = findViewById(R.id.tvConnectStatus)
        progress = findViewById(R.id.progressScan)
    }

    fun attachTo(owner: LifecycleOwner) {
        if (attached) return
        attached = true
        owner.lifecycleScope.launch {
            NexusNetPresenter.state.collectLatest { state ->
                progress.visibility = if (state.scanning) View.VISIBLE else View.GONE
                tvStatus.text = "Scan: ${state.scanStatus}"
                tvTarget.text = state.selected?.let {
                    "Target: ${NexusNetPresenter.label(it)} (${it.signalDbm} dBm)"
                } ?: "Target: -"
                tvConnect.text = "Koneksi: ${state.connectStatus}"
            }
        }
    }
}