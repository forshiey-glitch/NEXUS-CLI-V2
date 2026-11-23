package com.nexus.cli

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class VoiceForegroundService : Service() {

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { onIdleTimeout() }
    private var voiceHandler: VoiceCommandHandler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            saveMicState(false)
            broadcast(ACTION_MIC_OFF)
            shutdown()
            return START_NOT_STICKY
        }

        if (!startInForeground()) {
            saveMicState(false)
            broadcast(ACTION_MIC_OFF)
            shutdown()
            return START_NOT_STICKY
        }

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            saveMicState(false)
            broadcast(ACTION_MIC_OFF)
            shutdown()
            return START_NOT_STICKY
        }

        if (voiceHandler == null) {
            val handler = VoiceCommandHandler(
                context = this,
                onHeard = { text, isFinal ->
                    broadcast(ACTION_VOICE_HEARD) {
                        putExtra(EXTRA_TEXT, text)
                        putExtra(EXTRA_FINAL, isFinal)
                    }
                },
                onCommand = { cmd ->
                    broadcast(ACTION_VOICE_COMMAND) { putExtra(EXTRA_COMMAND, cmd) }
                },
                onSpeechDetected = { resetIdleTimer() },
                onFatalError = {
                    saveMicState(false)
                    broadcast(ACTION_MIC_OFF)
                    shutdown()
                }
            )
            if (!handler.start()) {
                saveMicState(false)
                broadcast(ACTION_MIC_OFF)
                shutdown()
                return START_NOT_STICKY
            }
            voiceHandler = handler
        }

        saveMicState(true)
        resetIdleTimer()
        return START_STICKY
    }

    private fun startInForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Nexus Voice", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Voice Active")
            .setContentText("Mendengarkan \"Hey Nexus\"")
            .setSmallIcon(R.drawable.ic_mic_on)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Matikan", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private fun onIdleTimeout() {
        saveMicState(false)
        broadcast(ACTION_MIC_OFF)
        shutdown()
    }

    private fun shutdown() {
        idleHandler.removeCallbacks(idleRunnable)
        voiceHandler?.stop()
        voiceHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveMicState(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MIC_ENABLED, enabled)
            .apply()
    }

    private fun broadcast(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(action).setPackage(packageName)
        extras?.invoke(intent)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleRunnable)
        voiceHandler?.stop()
        voiceHandler = null
        super.onDestroy()
    }

    companion object {
        const val PREFS_NAME = "nexus_prefs"
        const val KEY_MIC_ENABLED = "mic_enabled"

        const val ACTION_STOP = "com.nexus.cli.ACTION_VOICE_STOP"
        const val ACTION_MIC_OFF = "com.nexus.cli.ACTION_MIC_OFF"
        const val ACTION_VOICE_HEARD = "com.nexus.cli.ACTION_VOICE_HEARD"
        const val ACTION_VOICE_COMMAND = "com.nexus.cli.ACTION_VOICE_COMMAND"

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_FINAL = "extra_final"
        const val EXTRA_COMMAND = "extra_command"

        const val IDLE_TIMEOUT_MS = 3L * 60 * 60 * 1000

        private const val CHANNEL_ID = "nexus_voice"
        private const val NOTIF_ID = 2001
    }
}