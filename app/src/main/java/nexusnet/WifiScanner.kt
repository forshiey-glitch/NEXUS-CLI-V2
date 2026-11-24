package com.nexus.cli.nexusnet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class WifiTarget(
    val ssid: String,
    val bssid: String,
    val channel: Int,
    val signalDbm: Int,
    val encryption: String,
    val isSaved: Boolean
) {
    val isHidden: Boolean get() = ssid.isBlank()
    val isOpen: Boolean get() = encryption == "OPEN"
}

data class ScanOutcome(
    val targets: List<WifiTarget>,
    val fromCache: Boolean,
    val note: String?
)

/** Scan Wi-Fi via WifiManager. Cache 3 detik agar tidak kena throttle Android 11. */
class WifiScanner(context: Context) {

    private val app = context.applicationContext
    private val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mutex = Mutex()

    private var cache: List<WifiTarget> = emptyList()
    private var cacheTime = 0L

    @Suppress("DEPRECATION")
    suspend fun scan(): ScanOutcome = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        if (cacheTime != 0L && now - cacheTime < CACHE_MS) {
            return@withLock ScanOutcome(cache, true, "cache")
        }
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return@withLock ScanOutcome(emptyList(), false, "izin lokasi belum diberikan")
        }

        var note: String? = null
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                done.complete(Unit)
            }
        }
        // Daftarkan receiver SEBELUM startScan() agar broadcast tidak terlewat.
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val started = try {
                wifiManager.startScan()
            } catch (e: SecurityException) {
                false
            }
            if (started) {
                if (withTimeoutOrNull(SCAN_TIMEOUT_MS) { done.await() } == null) {
                    note = "timeout scan, pakai hasil terakhir sistem"
                }
            } else {
                note = "startScan() di-throttle Android, pakai hasil terakhir sistem"
            }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }

        val raw: List<ScanResult> = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            emptyList()
        }
        cache = normalize(raw)
        cacheTime = SystemClock.elapsedRealtime()
        ScanOutcome(cache, false, note)
    }

    @Suppress("DEPRECATION")
    private fun normalize(results: List<ScanResult>): List<WifiTarget> {
        val saved = savedSsids()
        return results.asSequence()
            .filter { it.BSSID != null }
            .distinctBy { it.BSSID }
            .map { r ->
                val ssid = r.SSID.orEmpty()
                WifiTarget(
                    ssid = ssid,
                    bssid = r.BSSID,
                    channel = freqToChannel(r.frequency),
                    signalDbm = r.level,
                    encryption = parseEncryption(r.capabilities.orEmpty()),
                    isSaved = ssid.isNotBlank() && ssid in saved
                )
            }
            .sortedByDescending { it.signalDbm }
            .toList()
    }

    /** Android 10+ mengembalikan daftar kosong untuk app non-sistem, jadi isSaved bisa selalu false. */
    @Suppress("DEPRECATION")
    private fun savedSsids(): Set<String> = try {
        wifiManager.configuredNetworks.orEmpty()
            .mapNotNull { it.SSID?.trim('"') }
            .toSet()
    } catch (e: SecurityException) {
        emptySet()
    }

    companion object {
        const val CACHE_MS = 3_000L
        private const val SCAN_TIMEOUT_MS = 10_000L

        fun freqToChannel(freq: Int): Int = when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5160..5885 -> (freq - 5000) / 5
            freq in 5955..7115 -> (freq - 5950) / 5
            else -> -1
        }

        fun parseEncryption(capabilities: String): String {
            val c = capabilities.uppercase(Locale.US)
            return when {
                "OWE" in c -> "OWE"
                "EAP" in c -> "ENTERPRISE"
                "SAE" in c && "PSK" in c -> "WPA2/WPA3"
                "SAE" in c -> "WPA3"
                "WPA2" in c || "RSN" in c -> "WPA2"
                "WPA" in c -> "WPA"
                "WEP" in c -> "WEP"
                else -> "OPEN"
            }
        }
    }
}