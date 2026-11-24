package com.nexus.cli.nexusnet

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log aktivitas scan/connect ke filesDir/lab.log. JANGAN PERNAH mengirim password ke sini. */
class LabLogger(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun log(event: String, detail: String = "") {
        val clean = detail.replace('\n', ' ').replace('\r', ' ')
        val line = "${stamp()} [${event.uppercase(Locale.US)}] $clean".trimEnd()
        synchronized(LOCK) {
            try {
                rotateIfNeeded()
                file.appendText(line + "\n")
            } catch (e: IOException) {
                // log gagal tidak boleh menjatuhkan fitur utama
            }
        }
    }

    fun tail(n: Int = 20): List<String> = synchronized(LOCK) {
        try {
            if (!file.exists()) emptyList() else file.readLines().takeLast(n)
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun clear() = synchronized(LOCK) {
        runCatching { if (file.exists()) file.delete() }
        Unit
    }

    private fun rotateIfNeeded() {
        if (file.exists() && file.length() > MAX_BYTES) {
            val keep = file.readLines().takeLast(KEEP_LINES)
            file.writeText(keep.joinToString("\n") + "\n")
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val FILE_NAME = "lab.log"
        const val MAX_BYTES = 256 * 1024L
        const val KEEP_LINES = 500
        val LOCK = Any()
    }
}