package com.nexus.cli

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Harus dipakai dari main thread (SpeechRecognizer mewajibkan ini).
 *
 * "Nexus, next/skip/previous/play/pause" dieksekusi LANGSUNG di latar belakang lewat MediaRepository:
 * tidak dicetak ke terminal dan tidak memindahkan halaman. Perintah lain tetap lewat [onCommand].
 */
class VoiceCommandHandler(
    context: Context,
    private val onHeard: (text: String, isFinal: Boolean) -> Unit,
    private val onCommand: (command: String) -> Unit,
    private val onSpeechDetected: () -> Unit,
    private val onFatalError: () -> Unit
) {

    private enum class MediaAction { NEXT, PREV, PLAY, PAUSE }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val guardHandler = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastPartial = ""
    private var lastPartialTime = 0L

    private var beepMuted = false
    private var wasMusicActive = false
    private var guardBlockedUntil = 0L
    private var lastStealAt = 0L
    private var lastMedia: MediaAction? = null
    private var lastMediaAt = 0L

    private val wakeRegex = Regex("""\b(?:hey\s+nexus|nexus)\b[\s,.:;!?-]*(.*)""", RegexOption.IGNORE_CASE)

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return false
        if (listening) return true
        MediaRepository.attach(appContext)
        listening = true
        startListening()
        return true
    }

    fun stop() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        guardHandler.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
        setBeepMuted(false)
    }

    private fun startListening() {
        if (!listening) return
        recognizer?.let {
            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        sr.setRecognitionListener(listener)
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        // "Musik seharusnya jalan" = sedang main, ATAU baru saja dicuri mic (jangan lupa saat restart),
        // KECUALI user baru menekan pause/next/dll (ikuti keinginan user).
        val now = SystemClock.uptimeMillis()
        val activeNow = audio.isMusicActive
        wasMusicActive = when {
            now - MediaRepository.lastUserActionAt < USER_ACTION_WINDOW_MS -> activeNow
            activeNow -> true
            now - lastStealAt < STEAL_MEMORY_MS -> wasMusicActive
            else -> false
        }
        setBeepMuted(true)
        try {
            sr.startListening(intent)
        } catch (_: Exception) {
            scheduleRestart(RESTART_DELAY_ERROR_MS)
        }

        // SpeechRecognizer sistem meminta audio focus sendiri tiap sesi dan bisa menjeda lagu.
        // Kita TIDAK meminta focus (dan tidak memakai MODE_IN_COMMUNICATION); sebagai gantinya
        // lagu dilanjutkan otomatis beberapa kali setelah sesi mulai dan setelah sesi selesai.
        scheduleGuard(GUARD_AFTER_START_MS)
    }

    private fun scheduleGuard(delaysMs: LongArray) {
        guardHandler.removeCallbacksAndMessages(null)
        delaysMs.forEach { d -> guardHandler.postDelayed({ guardMusic() }, d) }
    }

    private fun guardMusic() {
        if (!listening || !wasMusicActive) return
        val now = SystemClock.uptimeMillis()
        if (now < guardBlockedUntil) return
        if (now - MediaRepository.lastUserActionAt < USER_ACTION_WINDOW_MS) return
        if (!audio.isMusicActive) {
            lastStealAt = now
            MediaRepository.resumeSilently()
        }
    }

    /** Matikan bunyi beep recognizer (hanya stream NOTIFICATION/SYSTEM; STREAM_MUSIC tidak disentuh). */
    private fun setBeepMuted(mute: Boolean) {
        if (mute == beepMuted) return
        try {
            val flag = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, flag, 0)
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, flag, 0)
            beepMuted = mute
        } catch (_: SecurityException) {
            // Mode Do Not Disturb membatasi perubahan volume; abaikan.
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!listening) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ startListening() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            onSpeechDetected()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            scheduleGuard(GUARD_AFTER_END_MS)
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listening = false
                    setBeepMuted(false)
                    onFatalError()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(RESTART_DELAY_BUSY_MS)
                else -> scheduleRestart(RESTART_DELAY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) return
            onSpeechDetected()

            val wake = wakeRegex.find(text)
            if (wake != null) {
                // Ucapan berawalan "Nexus": jangan dicetak ke terminal; perintah media langsung jalan.
                parseMedia(normalize(wake.groupValues[1]))?.let { runMedia(it) }
                return
            }

            val now = System.currentTimeMillis()
            if (text != lastPartial && now - lastPartialTime >= PARTIAL_THROTTLE_MS) {
                lastPartial = text
                lastPartialTime = now
                onHeard(text, false)
            }
        }

        override fun onResults(results: Bundle?) {
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            lastPartial = ""
            scheduleGuard(GUARD_AFTER_END_MS)

            if (candidates.isNotEmpty()) {
                onSpeechDetected()

                val media = candidates.firstNotNullOfOrNull { c ->
                    wakeRegex.find(c)?.let { parseMedia(normalize(it.groupValues[1])) }
                }
                if (media != null) {
                    runMedia(media)
                } else {
                    val best = candidates.first().trim()
                    if (best.isNotEmpty()) onHeard(best, true)

                    val command = candidates.firstNotNullOfOrNull { extractCommand(it) }
                    if (command != null) onCommand(command)
                }
            }
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    private fun normalize(rest: String): String =
        rest.trim().trimEnd('.', ',', '!', '?').lowercase(Locale.ROOT)

    private fun parseMedia(rest: String): MediaAction? = when (rest) {
        "skip", "next", "skip song", "next song", "next track", "skip track", "skip this", "skip it" ->
            MediaAction.NEXT
        "previous", "prev", "back", "previous song", "previous track", "go back" -> MediaAction.PREV
        "play", "resume", "play music" -> MediaAction.PLAY
        "pause", "pause music", "stop music" -> MediaAction.PAUSE
        else -> null
    }

    private fun runMedia(action: MediaAction) {
        val now = SystemClock.uptimeMillis()
        // Partial dan final bisa memuat perintah yang sama; abaikan duplikat.
        if (action == lastMedia && now - lastMediaAt < MEDIA_DEBOUNCE_MS) return
        lastMedia = action
        lastMediaAt = now
        guardBlockedUntil = now + GUARD_BLOCK_MS

        when (action) {
            MediaAction.NEXT -> MediaRepository.next()
            MediaAction.PREV -> MediaRepository.previous()
            MediaAction.PLAY -> MediaRepository.play()
            MediaAction.PAUSE -> MediaRepository.pause()
        }
    }

    /** Perintah non-media: "Nexus clear screen" -> "clear"; "Nexus time" -> "time". */
    fun extractCommand(spoken: String): String? {
        val match = wakeRegex.find(spoken) ?: return null
        val rest = normalize(match.groupValues[1])
        if (rest.isEmpty()) return null
        return when (rest) {
            "clear screen" -> "clear"
            else -> rest
        }
    }

    companion object {
        private const val LANGUAGE_TAG = "en-US"
        private const val RESTART_DELAY_MS = 300L
        private const val RESTART_DELAY_BUSY_MS = 2000L
        private const val RESTART_DELAY_ERROR_MS = 1000L
        private const val PARTIAL_THROTTLE_MS = 300L
        private val GUARD_AFTER_START_MS = longArrayOf(500L, 1200L, 2200L, 3500L)
        private val GUARD_AFTER_END_MS = longArrayOf(400L, 1100L)
        private const val USER_ACTION_WINDOW_MS = 5000L
        private const val STEAL_MEMORY_MS = 8000L
        private const val MEDIA_DEBOUNCE_MS = 2500L
        private const val GUARD_BLOCK_MS = 4000L
    }
}