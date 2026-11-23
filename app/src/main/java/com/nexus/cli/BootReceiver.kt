package com.nexus.cli

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(VoiceForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        val micWanted = prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false)
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micWanted || !hasPermission) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceForegroundService::class.java)
            )
        } catch (_: Exception) {
            // Android 15+ melarang service microphone dari BOOT_COMPLETED.
            // Fallback: MainActivity menyalakan ulang mic saat launcher terbuka.
        }
    }
}