package com.nexus.cli.nexusnet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nexus.cli.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground service: scan Wi-Fi tetap hidup saat layar mati. */
class NexusNetService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private lateinit var scanner: WifiScanner
    private lateinit var repo: NexusNetRepository
    private lateinit var logger: LabLogger
    private lateinit var guard: LabGuard

    override fun onCreate() {
        super.onCreate()
        scanner = WifiScanner(this)
        repo = NexusNetRepository(NexusNetDatabase.getInstance(this).dao())
        logger = LabLogger(this)
        guard = LabGuard(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            loopJob?.cancel()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            logger.log("SERVICE_ERROR", e.message ?: e.javaClass.simpleName)
            NexusNetPresenter.onScanNote("service gagal: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        startLoop()
        return START_NOT_STICKY
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            logger.log("SCAN_START", "interval=${INTERVAL_MS / 1000}s")
            NexusNetPresenter.onScanStarted()
            while (isActive) {
                val status = guard.check()
                if (status != LabGuard.Status.READY) {
                    NexusNetPresenter.onScanNote(guard.message(status))
                    logger.log("SCAN_BLOCKED", status.name)
                } else {
                    val outcome = scanner.scan()
                    NexusNetPresenter.onScanResult(outcome.targets, outcome.note)
                    if (!outcome.fromCache && outcome.targets.isNotEmpty()) {
                        try {
                            repo.simpanHasil(outcome.targets)
                        } catch (e: Exception) {
                            logger.log("DB_ERROR", e.message ?: e.javaClass.simpleName)
                        }
                    }
                    logger.log(
                        "SCAN",
                        "found=${outcome.targets.size} cache=${outcome.fromCache} note=${outcome.note ?: "-"}"
                    )
                }
                delay(INTERVAL_MS)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NexusNetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus-Net Active") // TODO: pindahkan ke strings.xml
            .setContentText("Scan Wi-Fi berjalan di latar belakang")
            .setSmallIcon(android.R.drawable.ic_menu_search) // TODO: ganti ikon sendiri
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nexus-Net", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        NexusNetPresenter.onScanStopped()
        logger.log("SCAN_STOP")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nexus.cli.nexusnet.START"
        const val ACTION_STOP = "com.nexus.cli.nexusnet.STOP"
        private const val CHANNEL_ID = "nexus_net_channel"
        private const val NOTIF_ID = 4107

        // Android 9+ membatasi 4 scan / 2 menit untuk app foreground, jadi 35 detik aman.
        private const val INTERVAL_MS = 35_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NexusNetService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NexusNetService::class.java))
        }
    }
}