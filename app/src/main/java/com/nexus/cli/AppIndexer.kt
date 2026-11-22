package com.nexus.cli

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class AppEntry(
    val label: String,
    val packageName: String,
    val launchIntent: Intent
)

object AppIndexer {

    @Volatile
    private var cache: List<AppEntry> = emptyList()

    suspend fun refresh(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.applicationContext.packageManager
        val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val result = apps.mapNotNull { info ->
            val intent = pm.getLaunchIntentForPackage(info.packageName)
                ?: pm.getLeanbackLaunchIntentForPackage(info.packageName)
                ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            val label = pm.getApplicationLabel(info).toString()
            AppEntry(label, info.packageName, intent)
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }

        cache = result
        result
    }

    fun getCached(): List<AppEntry> = cache

    fun find(query: String): AppEntry? {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return null
        val list = cache
        return list.firstOrNull { it.label.lowercase(Locale.getDefault()) == q }
            ?: list.firstOrNull { it.packageName.lowercase(Locale.getDefault()) == q }
            ?: list.firstOrNull { it.label.lowercase(Locale.getDefault()).startsWith(q) }
            ?: list.firstOrNull { it.label.lowercase(Locale.getDefault()).contains(q) }
            ?: list.firstOrNull { it.packageName.lowercase(Locale.getDefault()).contains(q) }
    }

    fun launch(context: Context, entry: AppEntry): Boolean {
        return try {
            val intent = Intent(entry.launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}