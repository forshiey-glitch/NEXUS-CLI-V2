package com.nexus.cli.nexusnet

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parser perintah `nexus-net <sub> [arg]`.
 * Sub: scan, stop [net], select <no>, connect, db [clear], log, help.
 */
class NexusNetCommand(context: Context) {

    private val app = context.applicationContext
    private val guard = LabGuard(app)
    private val logger = LabLogger(app)
    private val repo = NexusNetRepository(NexusNetDatabase.getInstance(app).dao())
    private val connector = WifiConnector(app) { onNetworkLost() }

    /** Diisi MainActivity untuk membuka dialog izin lokasi. */
    var onNeedLocationPermission: (() -> Unit)? = null

    /** True saat menunggu password diketik user (baris berikutnya = password). */
    @Volatile
    var isAwaitingPassword: Boolean = false
        private set

    private var pendingTarget: WifiTarget? = null

    suspend fun execute(args: List<String>): String {
        val banner = guard.consumeBannerIfFirstRun()
        val body = try {
            dispatch(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        return if (banner != null) banner + "\n\n" + body else body
    }

    private suspend fun dispatch(args: List<String>): String {
        return when (val sub = args.firstOrNull()?.lowercase()) {
            null, "help" -> NexusNetPresenter.helpText()
            "scan" -> if (args.size > 1) usage("scan") else scan()
            "stop" -> if (args.size > 2 || (args.size == 2 && args[1] != "net")) usage("stop [net]") else stop(args.getOrNull(1))
            "select" -> if (args.size != 2) usage("select <no>") else select(args[1])
            "connect" -> connect(args.drop(1))
            "db" -> if (args.size > 2 || (args.size == 2 && args[1] != "clear")) usage("db [clear]") else db(args.getOrNull(1))
            "log" -> if (args.size > 1) usage("log") else log()
            else -> "Sub-perintah tidak dikenal: $sub\n\n" + NexusNetPresenter.helpText()
        }
    }

    private fun usage(text: String) = "Penggunaan: nexus-net $text"

    private suspend fun scan(): String {
        val status = guard.check()
        if (status != LabGuard.Status.READY) {
            if (status == LabGuard.Status.NEED_LOCATION_PERMISSION) onNeedLocationPermission?.invoke()
            return guard.message(status)
        }
        val startedAt = System.currentTimeMillis()
        NexusNetService.start(app)
        val state = withTimeoutOrNull(WAIT_SCAN_MS) {
            NexusNetPresenter.state.first { it.lastScanAt >= startedAt }
        } ?: return "Timeout menunggu hasil scan. Pastikan izin notifikasi & lokasi aktif, lalu coba lagi."
        logger.log("SCAN_CMD", "targets=${state.targets.size}")
        return NexusNetPresenter.formatTable(state.targets, state.selected) +
                "\n\nScan berjalan di background (hentikan: nexus-net stop)." +
                "\nPilih target: nexus-net select <no>"
    }

    private fun stop(arg: String?): String {
        NexusNetService.stop(app)
        isAwaitingPassword = false
        pendingTarget = null
        if (arg == "net") {
            connector.release()
            NexusNetPresenter.setConnectStatus("Tidak terhubung")
            logger.log("RELEASE", "koneksi lab dilepas")
            return "Scan dihentikan dan koneksi lab dilepas."
        }
        return "Scan dihentikan."
    }

    private fun select(arg: String): String {
        val no = arg.toIntOrNull() ?: return usage("select <no>")
        val targets = NexusNetPresenter.state.value.targets
        if (targets.isEmpty()) return "Belum ada hasil scan. Jalankan: nexus-net scan"
        val target = NexusNetPresenter.select(no)
            ?: return "Nomor tidak valid. Pilih 1..${targets.size}."
        logger.log("SELECT", "ssid=${NexusNetPresenter.label(target)} bssid=${target.bssid}")
        return NexusNetPresenter.formatSelected(target)
    }

    private suspend fun connect(extra: List<String>): String {
        if (extra.isNotEmpty()) {
            return "Jangan menulis password di baris perintah. Ketik hanya: nexus-net connect"
        }
        val target = NexusNetPresenter.state.value.selected
            ?: return "Belum ada target. Jalankan 'nexus-net scan' lalu 'nexus-net select <no>'."
        connector.unsupportedReason(target)?.let { return it }

        val status = guard.check()
        if (status != LabGuard.Status.READY) {
            if (status == LabGuard.Status.NEED_LOCATION_PERMISSION) onNeedLocationPermission?.invoke()
            return guard.message(status)
        }
        if (target.isOpen) return doConnect(target, null)

        pendingTarget = target
        isAwaitingPassword = true
        return "Masukkan password untuk '${NexusNetPresenter.label(target)}' " +
                "(8-63 karakter, TIDAK disimpan) atau ketik 'cancel':"
    }

    /** Dipanggil ShellEngine untuk baris input berikutnya saat [isAwaitingPassword] true. */
    suspend fun handleInput(line: String): String {
        val target = pendingTarget
        if (target == null) {
            isAwaitingPassword = false
            return "Tidak ada permintaan password aktif."
        }
        if (line.equals("cancel", ignoreCase = true)) {
            isAwaitingPassword = false
            pendingTarget = null
            return "Koneksi dibatalkan."
        }
        if (line.length !in 8..63) {
            return "Panjang passphrase WPA harus 8-63 karakter. Coba lagi atau ketik 'cancel':"
        }
        isAwaitingPassword = false
        pendingTarget = null
        return doConnect(target, line)
    }

    private suspend fun doConnect(target: WifiTarget, password: String?): String {
        val name = NexusNetPresenter.label(target)
        NexusNetPresenter.setConnectStatus("Menghubungkan ke $name...")
        logger.log("CONNECT_TRY", "ssid=$name bssid=${target.bssid}") // password TIDAK pernah dicatat
        return when (val result = connector.connect(target, password)) {
            is ConnectResult.Success -> {
                NexusNetPresenter.setConnectStatus("Terhubung: ${result.ssid}")
                logger.log("CONNECT_OK", "ssid=${result.ssid}")
                "Terhubung ke ${result.ssid}."
            }
            is ConnectResult.Failed -> {
                NexusNetPresenter.setConnectStatus("Gagal: ${result.reason}")
                logger.log("CONNECT_FAIL", "ssid=$name reason=${result.reason}")
                "Gagal terhubung: ${result.reason}"
            }
        }
    }

    private suspend fun db(arg: String?): String {
        if (arg == "clear") {
            repo.bersihkan()
            logger.log("DB_CLEAR")
            return "Riwayat scan dihapus."
        }
        return NexusNetPresenter.formatDb(repo.ambilTerbaru(20))
    }

    private fun log(): String {
        val lines = logger.tail(20)
        return if (lines.isEmpty()) "(lab.log masih kosong)" else lines.joinToString("\n")
    }

    private fun onNetworkLost() {
        NexusNetPresenter.setConnectStatus("Terputus")
        logger.log("DISCONNECT", "jaringan lab terputus")
    }

    private companion object {
        const val WAIT_SCAN_MS = 20_000L
    }
}