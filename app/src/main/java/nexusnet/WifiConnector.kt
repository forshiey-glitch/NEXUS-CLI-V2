package com.nexus.cli.nexusnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class ConnectResult {
    data class Success(val ssid: String) : ConnectResult()
    data class Failed(val reason: String) : ConnectResult()
}

/**
 * Menyambung ke Wi-Fi dengan WifiNetworkSpecifier.
 * Password HANYA berupa parameter fungsi: tidak disimpan di field, DB, maupun log.
 */
class WifiConnector(
    context: Context,
    private val onLost: () -> Unit = {}
) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun unsupportedReason(t: WifiTarget): String? = when {
        t.isHidden -> "SSID tersembunyi tidak didukung (butuh nama jaringan)."
        t.encryption == "WEP" -> "WEP tidak didukung oleh WifiNetworkSpecifier."
        t.encryption == "ENTERPRISE" -> "Jaringan Enterprise (EAP) tidak didukung modul ini."
        t.encryption == "OWE" -> "OWE (Enhanced Open) belum didukung."
        else -> null
    }

    suspend fun connect(target: WifiTarget, password: String?): ConnectResult {
        unsupportedReason(target)?.let { return ConnectResult.Failed(it) }

        val builder = WifiNetworkSpecifier.Builder().setSsid(target.ssid)
        if (!target.isOpen) {
            val pw = password.orEmpty()
            if (pw.length !in 8..63) {
                return ConnectResult.Failed("passphrase harus 8-63 karakter")
            }
            if (target.encryption == "WPA3") builder.setWpa3Passphrase(pw)
            else builder.setWpa2Passphrase(pw)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()

        release()

        return suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    if (cont.isActive) cont.resume(ConnectResult.Success(target.ssid))
                }

                override fun onUnavailable() {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    if (activeCallback === this) activeCallback = null
                    if (cont.isActive) {
                        cont.resume(
                            ConnectResult.Failed(
                                "jaringan tidak tersedia, ditolak, atau timeout ${TIMEOUT_MS / 1000} detik"
                            )
                        )
                    }
                }

                override fun onLost(network: Network) {
                    cm.bindProcessToNetwork(null)
                    onLost()
                }
            }
            activeCallback = callback
            cont.invokeOnCancellation { release() }
            try {
                cm.requestNetwork(request, callback, TIMEOUT_MS.toInt())
            } catch (e: Exception) {
                activeCallback = null
                if (cont.isActive) {
                    cont.resume(ConnectResult.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun release() {
        activeCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        activeCallback = null
        runCatching { cm.bindProcessToNetwork(null) }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}