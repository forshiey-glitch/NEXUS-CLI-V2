package com.nexus.cli

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.net.TrafficStats
import java.io.File

/**
 * Catatan: Android 8+ memblokir /proc/stat untuk app biasa, jadi CPU memakai fallback:
 * /proc/stat -> rasio frekuensi cpufreq -> CPU time proses ini. GPU hanya terbaca di
 * perangkat yang membuka sysfs-nya (mis. Adreno); selain itu bernilai -1 (N/A).
 */
class SystemMonitor(private val context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** SSID dari callback jaringan default (Android 12+ hanya terbaca lewat FLAG_INCLUDE_LOCATION_INFO). */
    @Volatile
    private var callbackSsid: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var lastStatTotal = 0L
    private var lastStatIdle = 0L

    private var lastProcCpuMs = Process.getElapsedCpuTime()
    private var lastProcWallMs = SystemClock.elapsedRealtime()

    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    private var lastNetNs = System.nanoTime()

    private val gpuPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/kernel/gpu/gpu_busy"
    )

    init {
        registerNetworkCallback()
    }

    /** Wajib dipanggil saat Activity dihancurkan. */
    fun close() {
        networkCallback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    fun sample(): MetricsSnapshot {
        val (usedMb, totalMb) = memoryUsageMb()
        val (battery, charging) = batteryInfo()
        val (rx, tx) = readThroughput()
        return MetricsSnapshot(
            cpuPct = readCpu(),
            gpuPct = readGpu(),
            ramPct = if (totalMb > 0) usedMb * 100f / totalMb else 0f,
            ramUsedMb = usedMb,
            ramTotalMb = totalMb,
            batteryPct = battery,
            charging = charging,
            netLabel = readNetLabel(),
            rxKbps = rx,
            txKbps = tx,
            cores = cores
        )
    }

    // ---------- RAM ----------

    fun memoryUsageMb(): Pair<Long, Long> {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val total = info.totalMem / (1024 * 1024)
        val used = (info.totalMem - info.availMem) / (1024 * 1024)
        return used to total
    }

    // ---------- Battery ----------

    /** Pair(persen atau -1, sedang charging). */
    fun batteryInfo(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return pct to charging
    }

    // ---------- Network ----------

    private fun registerNetworkCallback() {
        try {
            val cb: ConnectivityManager.NetworkCallback =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : ConnectivityManager.NetworkCallback(
                        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
                    ) {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            callbackSsid = (caps.transportInfo as? WifiInfo)?.ssid?.let { cleanSsid(it) }
                        }

                        override fun onLost(network: Network) {
                            callbackSsid = null
                        }
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onLost(network: Network) {
                            callbackSsid = null
                        }
                    }
                }
            connectivity.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    /** Jaringan aktif yang punya INTERNET; fallback ke jaringan lain bila activeNetwork null (OEM/VPN). */
    @Suppress("DEPRECATION")
    private fun currentCaps(): NetworkCapabilities? {
        val active = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (active != null && active.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return active
        return connectivity.allNetworks.asSequence()
            .mapNotNull { connectivity.getNetworkCapabilities(it) }
            .firstOrNull { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
    }

    private fun cleanSsid(raw: String): String? {
        val s = raw.trim().trim('"')
        return if (s.isEmpty() || s.equals("<unknown ssid>", ignoreCase = true)) null else s
    }

    @Suppress("DEPRECATION")
    private fun legacySsid(): String? = try {
        wifiManager.connectionInfo?.ssid?.let { cleanSsid(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Wi-Fi -> nama SSID, seluler -> "CELL", tidak ada jaringan -> "OFFLINE".
     * Tanda " !" bila terhubung tetapi belum VALIDATED (mis. captive portal / tanpa internet).
     */
    private fun readNetLabel(): String {
        val caps = currentCaps() ?: return "OFFLINE"
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                (callbackSsid ?: legacySsid() ?: "WIFI").take(MAX_SSID_CHARS)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "ONLINE"
        }
        return if (validated) base else "$base !"
    }

    /** Pair(rx KB/s, tx KB/s). */
    private fun readThroughput(): Pair<Float, Float> {
        val rxNow = TrafficStats.getTotalRxBytes()
        val txNow = TrafficStats.getTotalTxBytes()
        val nowNs = System.nanoTime()
        val dt = (nowNs - lastNetNs) / 1_000_000_000.0

        var rx = 0f
        var tx = 0f
        if (dt > 0 && rxNow >= 0 && lastRx >= 0) {
            rx = ((rxNow - lastRx) / 1024.0 / dt).toFloat().coerceAtLeast(0f)
        }
        if (dt > 0 && txNow >= 0 && lastTx >= 0) {
            tx = ((txNow - lastTx) / 1024.0 / dt).toFloat().coerceAtLeast(0f)
        }
        lastRx = rxNow
        lastTx = txNow
        lastNetNs = nowNs
        return rx to tx
    }

    // ---------- CPU ----------

    fun getCpuUsage(): Float = readCpu()

    private fun readCpu(): Float =
        readProcStat() ?: readFreqRatio() ?: readProcessCpu()

    private fun readProcStat(): Float? {
        val line = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } catch (e: Exception) {
            null
        } ?: return null

        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8 || parts[0] != "cpu") return null
        val nums = parts.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        val idle = nums[3] + nums[4]
        val total = nums.sum()

        val dTotal = total - lastStatTotal
        val dIdle = idle - lastStatIdle
        val first = lastStatTotal == 0L
        lastStatTotal = total
        lastStatIdle = idle
        if (first || dTotal <= 0) return 0f
        return ((dTotal - dIdle) * 100f / dTotal).coerceIn(0f, 100f)
    }

    /** Rata-rata frekuensi saat ini / frekuensi maksimum (perkiraan beban). */
    private fun readFreqRatio(): Float? {
        var sum = 0f
        var n = 0
        for (i in 0 until cores) {
            val cur = readText("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()
            val max = readText("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
            if (cur != null && max != null && max > 0) {
                sum += cur.toFloat() / max
                n++
            }
        }
        return if (n > 0) (sum / n * 100f).coerceIn(0f, 100f) else null
    }

    private fun readProcessCpu(): Float {
        val cpuNow = Process.getElapsedCpuTime()
        val wallNow = SystemClock.elapsedRealtime()
        val dCpu = cpuNow - lastProcCpuMs
        val dWall = wallNow - lastProcWallMs
        lastProcCpuMs = cpuNow
        lastProcWallMs = wallNow
        if (dWall <= 0) return 0f
        return (dCpu * 100f / dWall / cores).coerceIn(0f, 100f)
    }

    // ---------- GPU ----------

    private fun readGpu(): Float {
        for (path in gpuPaths) {
            val text = readText(path) ?: continue
            val value = text.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            return value.toFloat().coerceIn(0f, 100f)
        }
        return -1f
    }

    private fun readText(path: String): String? = try {
        File(path).readText().trim()
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_SSID_CHARS = 14
    }
}