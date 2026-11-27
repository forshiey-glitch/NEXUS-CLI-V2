package com.nexus.cli.nexusnet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Hasil satu kali baca daftar Wi-Fi untuk Nexus-Net Panel. */
data class WifiSnapshot(
    val networks: List<WifiTarget> = emptyList(),
    val note: String? = null,
    /** Umur data dari scan terakhir sistem (detik); -1 jika tidak diketahui. */
    val dataAgeSec: Long = -1L,
    val updatedAtMs: Long = 0L
)

/**
 * Pembaca daftar Wi-Fi ringan untuk polling tiap ~1 detik. HANYA melihat jaringan (SSID, sinyal,
 * enkripsi, channel). Tidak ada uji password.
 *
 * Android membatasi startScan() (foreground: sekitar 4 kali per 2 menit), jadi radio tidak bisa
 * dipindai tiap detik. Yang dilakukan tiap detik adalah membaca scanResults terbaru dari sistem;
 * startScan() dipicu tiap [START_SCAN_EVERY_MS] agar kuota tidak habis dalam beberapa detik.
 */
class WifiLiveScanner(context: Context) {

    private val app = context.applicationContext
    private val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var lastStartScanAt = 0L

    @Suppress("DEPRECATION")
    fun poll(): WifiSnapshot {
        val nowWall = System.currentTimeMillis()
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return WifiSnapshot(note = "izin lokasi belum diberikan", updatedAtMs = nowWall)
        if (!wifiManager.isWifiEnabled) return WifiSnapshot(note = "Wi-Fi mati", updatedAtMs = nowWall)

        val now = SystemClock.elapsedRealtime()
        if (lastStartScanAt == 0L || now - lastStartScanAt >= START_SCAN_EVERY_MS) {
            lastStartScanAt = now
            try {
                wifiManager.startScan()
            } catch (_: SecurityException) {
            }
        }

        val raw: List<ScanResult> = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            emptyList()
        }

        if (raw.isEmpty()) {
            val note = if (!locationManager.isLocationEnabled) {
                "aktifkan Lokasi agar daftar Wi-Fi muncul"
            } else {
                "belum ada hasil scan"
            }
            return WifiSnapshot(note = note, updatedAtMs = nowWall)
        }

        val newestUs = raw.maxOf { it.timestamp }
        val ageSec = ((now - newestUs / 1000L) / 1000L).coerceAtLeast(0L)

        val targets = raw.asSequence()
            .filter { it.BSSID != null }
            .map { r ->
                WifiTarget(
                    ssid = r.SSID.orEmpty(),
                    bssid = r.BSSID,
                    channel = WifiScanner.freqToChannel(r.frequency),
                    signalDbm = r.level,
                    encryption = WifiScanner.parseEncryption(r.capabilities.orEmpty()),
                    isSaved = false
                )
            }
            // Satu baris per SSID (yang terkuat); SSID tersembunyi dibedakan lewat BSSID.
            .groupBy { if (it.ssid.isBlank()) it.bssid else it.ssid }
            .map { (_, group) -> group.maxBy { it.signalDbm } }
            .sortedByDescending { it.signalDbm }
            .take(MAX_ITEMS)
            .toList()

        return WifiSnapshot(networks = targets, dataAgeSec = ageSec, updatedAtMs = nowWall)
    }

    companion object {
        /** Interval minimal antar startScan() (ms). Turunkan hanya jika "Wi-Fi scan throttling" dimatikan di Developer options. */
        const val START_SCAN_EVERY_MS = 30_000L
        private const val MAX_ITEMS = 30
    }
}
