package com.nexus.cli.nexusnet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/** Penjaga prasyarat: izin ACCESS_FINE_LOCATION, GPS aktif, Wi-Fi aktif + banner [LAB MODE]. */
class LabGuard(context: Context) {

    enum class Status { READY, NEED_LOCATION_PERMISSION, GPS_OFF, WIFI_OFF }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun check(): Status {
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Status.NEED_LOCATION_PERMISSION

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) return Status.GPS_OFF

        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) return Status.WIFI_OFF

        return Status.READY
    }

    fun message(status: Status): String = when (status) {
        Status.READY -> "Siap."
        Status.NEED_LOCATION_PERMISSION ->
            "Izin ACCESS_FINE_LOCATION dibutuhkan (syarat Android untuk scan & koneksi Wi-Fi). " +
                    "Dialog izin dibuka: izinkan, lalu ulangi perintah."
        Status.GPS_OFF ->
            "Lokasi/GPS mati. Aktifkan Lokasi di pengaturan (wajib oleh Android), lalu ulangi."
        Status.WIFI_OFF ->
            "Wi-Fi mati. Aktifkan Wi-Fi, lalu ulangi."
    }

    /** Banner hanya muncul sekali (pertama kali modul aktif). */
    fun consumeBannerIfFirstRun(): String? {
        if (prefs.getBoolean(KEY_BANNER, false)) return null
        prefs.edit().putBoolean(KEY_BANNER, true).apply()
        return BANNER
    }

    companion object {
        private const val PREFS = "nexus_net_prefs"
        private const val KEY_BANNER = "banner_shown"

        const val BANNER = "[LAB MODE] NEXUS-NET aktif\n" +
                "Modul diagnostik: scan & connect Wi-Fi milik Anda sendiri.\n" +
                "Tanpa cracking. Password hanya diketik manual dan TIDAK disimpan.\n" +
                "Ketik 'nexus-net help' untuk daftar perintah."
    }
}