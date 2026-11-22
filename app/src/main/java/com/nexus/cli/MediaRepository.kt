package com.nexus.cli

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Singleton pengikat NotificationListenerService <-> UI. Semua dipanggil dari main thread. */
object MediaRepository {

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var hasAccess = false

    private val knownPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music"
    )

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            detach()
            publish()
        }
    }

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun setAccess(value: Boolean) {
        hasAccess = value
        publish()
    }

    /** Dipanggil dari Activity (onResume) untuk sinkronisasi ulang. */
    fun refresh(context: Context) {
        hasAccess = isAccessGranted(context)
        if (hasAccess) {
            try {
                val msm = context.getSystemService(MediaSessionManager::class.java)
                val component = ComponentName(context, NexusNotificationListener::class.java)
                onSessions(msm.getActiveSessions(component))
                return
            } catch (e: SecurityException) {
                hasAccess = false
            }
        }
        publish()
    }

    fun onSessions(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull { it.packageName in knownPackages }
            ?: list?.firstOrNull()

        if (best?.sessionToken != controller?.sessionToken) {
            detach()
            controller = best
            best?.registerCallback(callback, mainHandler)
        }
        publish()
    }

    fun clear() {
        detach()
        hasAccess = false
        publish()
    }

    private fun detach() {
        try {
            controller?.unregisterCallback(callback)
        } catch (e: Exception) {
            // abaikan
        }
        controller = null
    }

    // ---------- kontrol ----------

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    // ---------- state ----------

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = MediaState(hasAccess = hasAccess)
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        _state.value = MediaState(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: "Tanpa judul",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
                ?: "—",
            isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
            source = sourceLabel(c.packageName),
            positionMs = ps?.position ?: 0L,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            updatedAtMs = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed ?: 1f,
            hasAccess = hasAccess,
            hasSession = true
        )
    }

    private fun sourceLabel(pkg: String): String = when (pkg) {
        "com.spotify.music" -> "SPOTIFY"
        "com.google.android.apps.youtube.music" -> "YT MUSIC"
        else -> pkg.substringAfterLast('.').uppercase()
    }
}