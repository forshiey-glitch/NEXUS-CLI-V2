package com.nexus.cli

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.util.Locale

/**
 * Semua peluncuran app eksternal lewat [start], sehingga [onBeforeLaunch]
 * (mis. melepas LockTask) selalu dipanggil dulu.
 */
class AppRepository(
    private val context: Context,
    private val onBeforeLaunch: () -> Unit = {}
) {

    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences(
        VoiceForegroundService.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private fun installedApplications(): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }

    private fun launchIntentFor(packageName: String): Intent? {
        return pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
    }

    fun listApps(): List<AppInfo> {
        val result: List<AppInfo> = installedApplications()
            .filter { app: ApplicationInfo -> app.packageName != context.packageName }
            .filter { app: ApplicationInfo -> launchIntentFor(app.packageName) != null }
            .map { app: ApplicationInfo ->
                AppInfo(
                    label = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName
                )
            }
            .distinctBy { info: AppInfo -> info.packageName }
            .sortedBy { info: AppInfo -> info.label.lowercase(Locale.getDefault()) }
        return result
    }

    fun findApp(query: String): AppInfo? {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return null
        val apps: List<AppInfo> = listApps()
        return apps.firstOrNull { it.label.lowercase(Locale.getDefault()) == q }
            ?: apps.firstOrNull { it.packageName.lowercase(Locale.getDefault()) == q }
            ?: apps.firstOrNull { it.label.lowercase(Locale.getDefault()).startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase(Locale.getDefault()).contains(q) }
            ?: apps.firstOrNull { it.packageName.lowercase(Locale.getDefault()).contains(q) }
    }

    fun launchPackage(packageName: String): Boolean {
        val intent = launchIntentFor(packageName) ?: return false
        val ok = start(intent)
        if (ok) recordLaunch(packageName)
        return ok
    }

    fun launchFirstInstalled(vararg packages: String): Boolean {
        for (pkg in packages) {
            if (launchIntentFor(pkg) != null && launchPackage(pkg)) return true
        }
        return false
    }

    fun openUrl(url: String): Boolean = start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun start(intent: Intent): Boolean {
        return try {
            onBeforeLaunch()
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Hitung berapa kali app dibuka lewat launcher ini (dipakai untuk memilih app musik favorit). */
    private fun recordLaunch(packageName: String) {
        val key = MediaRepository.LAUNCH_COUNT_PREFIX + packageName
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }
}