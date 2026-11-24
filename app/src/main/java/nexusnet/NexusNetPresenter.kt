package com.nexus.cli.nexusnet

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LabUiState(
    val scanning: Boolean = false,
    val scanStatus: String = "Idle",
    val targets: List<WifiTarget> = emptyList(),
    val selected: WifiTarget? = null,
    val connectStatus: String = "Tidak terhubung",
    val lastScanAt: Long = 0L
)

/** Format output terminal + StateFlow untuk WifiLabCard (Halaman 4). */
object NexusNetPresenter {

    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    fun label(t: WifiTarget): String = t.ssid.ifBlank { "<hidden>" }

    fun onScanStarted() =
        _state.update { it.copy(scanning = true, scanStatus = "Memindai...") }

    fun onScanResult(targets: List<WifiTarget>, note: String?) = _state.update {
        it.copy(
            scanning = true,
            targets = targets,
            lastScanAt = System.currentTimeMillis(),
            scanStatus = "Aktif: ${targets.size} jaringan" + (note?.let { n -> " ($n)" } ?: "")
        )
    }

    fun onScanNote(text: String) =
        _state.update { it.copy(scanStatus = text, lastScanAt = System.currentTimeMillis()) }

    fun onScanStopped() =
        _state.update { it.copy(scanning = false, scanStatus = "Dihentikan") }

    fun select(no: Int): WifiTarget? {
        val t = _state.value.targets.getOrNull(no - 1) ?: return null
        _state.update { it.copy(selected = t) }
        return t
    }

    fun setConnectStatus(text: String) =
        _state.update { it.copy(connectStatus = text) }

    fun formatTable(targets: List<WifiTarget>, selected: WifiTarget?): String {
        if (targets.isEmpty()) return "Tidak ada jaringan ditemukan."
        val sb = StringBuilder()
        sb.appendLine(" NO  SSID                          CH   dBm  ENC        SAVED")
        sb.appendLine("-------------------------------------------------------------")
        targets.forEachIndexed { i, t ->
            val mark = if (selected?.bssid == t.bssid) ">" else " "
            sb.appendLine(
                String.format(
                    Locale.US, "%s%02d  %-28s %3d  %4d  %-9s  %s",
                    mark, i + 1, label(t).take(28), t.channel, t.signalDbm,
                    t.encryption, if (t.isSaved) "ya" else "-"
                )
            )
        }
        return sb.toString().trimEnd()
    }

    fun formatSelected(t: WifiTarget): String =
        "Target: ${label(t)} | ${t.bssid} | CH ${t.channel} | ${t.signalDbm} dBm | ${t.encryption}\n" +
                "Sambung dengan: nexus-net connect"

    fun formatDb(items: List<HasilScan>): String {
        if (items.isEmpty()) return "Riwayat scan kosong."
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine(" ID   WAKTU           SSID                          dBm  ENC")
        sb.appendLine("-------------------------------------------------------------")
        items.forEach {
            sb.appendLine(
                String.format(
                    Locale.US, "%4d  %-14s  %-28s %4d  %s",
                    it.id, fmt.format(Date(it.waktu)),
                    it.ssid.ifBlank { "<hidden>" }.take(28), it.sinyal, it.enkripsi
                )
            )
        }
        return sb.toString().trimEnd()
    }

    fun helpText(): String = """
        [LAB MODE] NEXUS-NET (legal, tanpa cracking)
          nexus-net scan          mulai scan (foreground service, refresh ±35 dtk)
          nexus-net stop [net]    hentikan scan; 'net' juga melepas koneksi lab
          nexus-net select <no>   pilih target dari tabel
          nexus-net connect       sambung ke target (password diketik manual, tidak disimpan)
          nexus-net db [clear]    riwayat scan (tanpa password)
          nexus-net log           20 baris terakhir lab.log
          nexus-net help          bantuan ini
    """.trimIndent()

    /** Warna neon #00FF66 untuk teks terminal. */
    fun neon(text: String): CharSequence = SpannableString(text).apply {
        setSpan(
            ForegroundColorSpan(Color.parseColor("#00FF66")),
            0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}