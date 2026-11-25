package com.nexus.cli

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Singleton pengikat NotificationListenerService <-> UI. Semua dipanggil dari main thread. */
object MediaRepository {

    /** Prefix key SharedPreferences untuk hitungan "app paling sering dibuka" (ditulis AppRepository). */
    const val LAUNCH_COUNT_PREFIX = "launch_count_"

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var controller: MediaController? = null
    private var hasAccess = false

    /** Waktu (uptimeMillis) aksi media terakhir dari pengguna/suara. Dipakai guard mic agar tidak melawan user. */
    @Volatile
    var lastUserActionAt = 0L
        private set

    private fun markUserAction() {
        lastUserActionAt = SystemClock.uptimeMillis()
    }

    private val knownPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music"
    )

    /** Urutan = prioritas saat hitungan launch seri. */
    private val knownMusicApps = listOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "deezer.android.app",
        "com.amazon.mp3",
        "com.soundcloud.android",
        "com.aspiro.tidal",
        "com.samsung.android.app.musicplayer",
        "com.miui.player"
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

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun setAccess(value: Boolean) {
        hasAccess = value
        publish()
    }

    /** Dipanggil dari Activity (onResume) untuk sinkronisasi ulang. */
    fun refresh(context: Context) {
        attach(context)
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

    // ---------- kontrol (fallback ke Media Button jika belum ada session) ----------

    fun playPause() {
        markUserAction()
        val c = controller
        if (c == null) {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun play() {
        markUserAction()
        val c = controller
        if (c != null) c.transportControls.play() else sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    /**
     * Dipakai guard mikrofon untuk melanjutkan lagu yang dihentikan SpeechRecognizer (audio focus).
     * TIDAK menandai aksi pengguna.
     */
    fun resumeSilently() {
        val c = controller
        if (c != null) c.transportControls.play() else sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause() {
        markUserAction()
        val c = controller
        if (c != null) c.transportControls.pause() else sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun next() {
        markUserAction()
        val c = controller
        if (c != null) c.transportControls.skipToNext() else sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        markUserAction()
        val c = controller
        if (c != null) c.transportControls.skipToPrevious() else sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun isMusicActive(): Boolean {
        val ctx = appContext ?: return false
        return (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
    }

    /** Kirim Media Button (down + up) lewat AudioManager.dispatchMediaKeyEvent. */
    fun sendKey(keyCode: Int) {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /**
     * Tombol Play di UI.
     * - Sedang main  -> pause.
     * - Tidak main   -> buka aplikasi musik yang paling sering dibuka ([launch]), lalu kirim Media Button Play.
     * Tidak menyentuh ViewPager.
     */
    fun onPlayPressed(launch: (String) -> Boolean) {
        val ctx = appContext ?: return
        if (_state.value.isPlaying) {
            pause()
            return
        }
        val pkg = mostUsedMusicPackage(ctx)
        if (pkg != null) launch(pkg)
        scope.launch {
            delay(1200L)
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            delay(1800L)
            if (!isMusicActive()) sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    /** Klik piringan/sampul: buka aplikasi musik yang paling sering dibuka (tanpa mengirim Play). */
    fun launchMostUsedMusic(launch: (String) -> Boolean): Boolean {
        val ctx = appContext ?: return false
        val pkg = mostUsedMusicPackage(ctx) ?: return false
        return launch(pkg)
    }

    @Suppress("DEPRECATION")
    private fun mostUsedMusicPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        val prefs = ctx.getSharedPreferences(VoiceForegroundService.PREFS_NAME, Context.MODE_PRIVATE)

        val installedKnown = knownMusicApps.filter { pm.getLaunchIntentForPackage(it) != null }
        val viaCategory = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0
        ).map { it.activityInfo.packageName }.filter { it != ctx.packageName }

        // maxByOrNull memilih elemen pertama saat seri -> Spotify jadi default.
        return (installedKnown + viaCategory).distinct()
            .maxByOrNull { prefs.getInt(LAUNCH_COUNT_PREFIX + it, 0) }
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
            hasSession = true,
            albumArt = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        )
    }

    private fun sourceLabel(pkg: String): String = when (pkg) {
        "com.spotify.music" -> "SPOTIFY"
        "com.google.android.apps.youtube.music" -> "YT MUSIC"
        else -> pkg.substringAfterLast('.').uppercase()
    }
}