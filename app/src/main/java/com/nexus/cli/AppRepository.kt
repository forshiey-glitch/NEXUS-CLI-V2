package com.nexus.cli

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Semua peluncuran app eksternal lewat [start], sehingga [onBeforeLaunch]
 * (mis. melepas LockTask) selalu dipanggil dulu.
 */
class AppRepository(
    private val context: Context,
    private val onBeforeLaunch: () -> Unit = {}
) {

    private val pm: PackageManager = context.packageManager

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
        return installedApplications()
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { launchIntentFor(it.packageName) != null }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun findApp(query: String): AppInfo? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val apps = listApps()
        return apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(q) }
    }

    fun launchPackage(packageName: String): Boolean {
        val intent = launchIntentFor(packageName) ?: return false
        return start(intent)
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
}