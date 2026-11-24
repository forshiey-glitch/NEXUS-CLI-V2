package com.nexus.cli

/*
 * ===================== INSTRUKSI PERUBAHAN (WAJIB) =====================
 * A) LAYOUT MEDIA PLAYER (XML):
 *   1. vinylDisc -> ShapeableImageView bulat:
 *      <com.google.android.material.imageview.ShapeableImageView
 *          android:id="@+id/vinylDisc" android:scaleType="centerCrop"
 *          app:shapeAppearanceOverlay="@style/Nexus.Circle" ... />
 *      <style name="Nexus.Circle" parent="">
 *          <item name="cornerFamily">rounded</item>
 *          <item name="cornerSize">50%</item>
 *      </style>
 *   2. Progress: horizontal ProgressBar neon (atau LinearProgressIndicator / di bawah SparklineView):
 *      <ProgressBar style="?android:attr/progressBarStyleHorizontal" android:max="10000"
 *          android:progressTint="#00FF66" android:progressBackgroundTint="#2200FF66" ... />
 *
 * B) NexusPagerAdapter.kt (file ini tidak ada di sini, tambahkan 4 hal):
 *   1. Saat bind Halaman 1:  NexusUi.makeCircular(vinylDisc)
 *   2. Klik: HAPUS listener onMediaCardClick dari btnPlayPause dan dari kartu;
 *            pasang HANYA di vinylDisc dan albumArt -> actions.onMediaCardClick()
 *            btnPlayPause tetap -> actions.onMediaPlayPause()  (kini hanya play/pause di tempat)
 *   3. Di setMedia(state):  NexusUi.animateProgress(mediaProgress, <isPlaying>, <positionMs>, <durationMs>)
 *                           NexusUi.spinVinyl(vinylDisc, <isPlaying>)
 *   4. Halaman 2: HAPUS pemicu kustomisasi saat swipe/scroll. Untuk setiap widget:
 *                 NexusUi.attachLongPress(widgetView) { actions.onQuickLaunchLongPress() }   // 5000 ms
 *                 (jangan pakai setOnLongClickListener). Ukuran widget: QuickWidgetStore.sizeOf(context, id)
 *                 -> cols/rows untuk span GridLayoutManager. setWidgets() dipanggil ulang saat disimpan.
 *   5. Halaman 4: tambahkan <com.nexus.cli.nexusnet.WifiLabCard android:id="@+id/wifiLabCard" .../>
 *                 lalu di bind: wifiLabCard.attachTo(wifiLabCard.context as LifecycleOwner)
 * ======================================================================
 */

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.nexus.cli.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap
import kotlin.math.hypot

class MainActivity : AppCompatActivity(), PageActions {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private lateinit var fs: VirtualFS
    private lateinit var apps: AppRepository
    private lateinit var weather: WeatherRepository
    private lateinit var monitor: SystemMonitor
    private lateinit var shell: ShellEngine
    private lateinit var pagerAdapter: NexusPagerAdapter
    private val terminalAdapter = TerminalAdapter()

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

    // Input terminal asli (dipulihkan setelah mode password)
    private var terminalInputType = InputType.TYPE_CLASS_TEXT

    // Kustomisasi Quick Launch
    private var widgets: Set<String> = QuickWidgets.default
    private var widgetSheet: QuickWidgetEditorSheet? = null

    // Cek launcher default (sekali per proses)
    private var launcherPromptShown = false

    // Sensor cahaya (hanya aktif jika widget "light_sensor" dipilih)
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var lightLastUpdate = 0L
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lightLastUpdate < 500L) return
            lightLastUpdate = now
            pagerAdapter.setLightLux(event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ---------- Voice receiver ----------

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                VoiceForegroundService.ACTION_MIC_OFF -> {
                    setMicState(false)
                    appendText("[voice] mikrofon dimatikan (idle timeout / dihentikan).", LineType.SYSTEM)
                }
                VoiceForegroundService.ACTION_VOICE_HEARD -> {
                    val text = intent.getStringExtra(VoiceForegroundService.EXTRA_TEXT).orEmpty()
                    val isFinal = intent.getBooleanExtra(VoiceForegroundService.EXTRA_FINAL, false)
                    appendText(if (isFinal) "[voice] \"$text\"" else "[voice~] $text", LineType.SYSTEM)
                }
                VoiceForegroundService.ACTION_VOICE_COMMAND -> {
                    val cmd = intent.getStringExtra(VoiceForegroundService.EXTRA_COMMAND).orEmpty()
                    if (shell.isAwaitingSecret) {
                        // Suara tidak boleh masuk sebagai password Wi-Fi.
                        appendText("[voice] perintah suara diabaikan: terminal menunggu password.", LineType.SYSTEM)
                    } else if (cmd.isNotBlank()) {
                        openTerminal(showKeyboard = false)
                        runCommand(cmd)
                    }
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMicPermission()) {
                startVoiceService()
                setMicState(true)
                appendText("[voice] mikrofon aktif. Ucapkan \"Hey Nexus, <perintah>\".", LineType.SYSTEM)
            } else {
                setMicState(false)
                appendText("[voice] izin RECORD_AUDIO ditolak.", LineType.ERROR)
            }
        }

    // Izin lokasi + notifikasi untuk nexus-net
    private val labPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (granted) {
                appendText("[nexus-net] izin lokasi diberikan. Ulangi perintah nexus-net.", LineType.SYSTEM)
            } else {
                appendText("[nexus-net] izin lokasi ditolak, scan/connect tidak bisa dipakai.", LineType.ERROR)
            }
        }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(VoiceForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        MediaRepository.attach(this)

        fs = VirtualFS(this)
        apps = AppRepository(this) { releaseKiosk() }
        weather = WeatherRepository()
        monitor = SystemMonitor(this)
        shell = ShellEngine(
            context = this,
            registry = CommandRegistry(),
            shellContext = ShellContext(applicationContext, fs, apps, weather, monitor),
            cpuProvider = { monitor.getCpuUsage() }
        )
        // Callback dipanggil dari thread background -> wajib pindah ke main thread.
        shell.nexusNet.onNeedLocationPermission = { runOnUiThread { requestLabPermissions() } }

        setupPager()
        setupTerminal()
        setupMic()
        setupBackHandler()

        startClockLoop()
        startMetricsLoop()
        startWeatherLoop()
        startMediaCollector()
    }

    override fun onResume() {
        super.onResume()
        MediaRepository.refresh(this)
        refreshMicIcon()
        syncLightSensor()
        promptDefaultLauncherIfNeeded()
    }

    override fun onPause() {
        sensorManager.unregisterListener(lightListener)
        super.onPause()
    }

    override fun onDestroy() {
        NexusBus.terminalPrinter = null
        NexusBus.terminalClearer = null
        NexusBus.micToggle = null
        NexusBus.matrixStart = null
        NexusBus.matrixStop = null
        binding.matrixRain.stopMatrixRain()
        widgetSheet?.dismiss()
        try {
            unregisterReceiver(voiceReceiver)
        } catch (_: IllegalArgumentException) {
        }
        shell.close()
        super.onDestroy()
    }

    // ---------- Launcher default (pengganti paksaan LockTask) ----------

    @Suppress("DEPRECATION")
    private fun isDefaultLauncher(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun promptDefaultLauncherIfNeeded() {
        if (launcherPromptShown || isDefaultLauncher()) return
        launcherPromptShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Set as Default Launcher")
            .setMessage("NEXUS-CLI belum menjadi launcher default. Buka pengaturan sekarang?")
            .setPositiveButton("Buka Pengaturan") { _, _ -> openHomeSettings() }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun openHomeSettings() {
        val ok = apps.start(Intent(Settings.ACTION_HOME_SETTINGS)) ||
                apps.start(Intent(Settings.ACTION_SETTINGS))
        if (!ok) toast("Pengaturan launcher tidak bisa dibuka")
    }

    // ---------- Pager ----------

    private fun setupPager() {
        pagerAdapter = NexusPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 3
        buildPageIndicator(pagerAdapter.itemCount)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
            }
        })

        widgets = loadWidgets()
        pagerAdapter.setWidgets(widgets)
    }

    private fun buildPageIndicator(count: Int) {
        val d = resources.displayMetrics.density
        binding.pageIndicator.removeAllViews()
        repeat(count) {
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                layoutParams = LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply {
                    marginStart = (4 * d).toInt()
                    marginEnd = (4 * d).toInt()
                }
                alpha = 0.3f
            }
            binding.pageIndicator.addView(dot)
        }
        updateIndicator(0)
    }

    private fun updateIndicator(selected: Int) {
        for (i in 0 until binding.pageIndicator.childCount) {
            binding.pageIndicator.getChildAt(i).alpha = if (i == selected) 1f else 0.3f
        }
    }

    // ---------- Kustomisasi widget Quick Launch ----------

    private fun loadWidgets(): Set<String> =
        prefs.getStringSet(KEY_WIDGETS, null)?.toSet() ?: QuickWidgets.default

    /** Dipicu HANYA oleh long press 5000 ms (NexusUi.attachLongPress di adapter). */
    override fun onQuickLaunchLongPress() {
        if (widgetSheet?.isShowing == true) return
        val candidates = LinkedHashSet<String>().apply {
            addAll(QuickWidgets.default)
            addAll(widgets)
            add("light_sensor")
        }.toList()

        widgetSheet = QuickWidgetEditorSheet(
            context = this,
            candidates = candidates,
            active = widgets,
            sizes = QuickWidgetStore.sizes(this),
            onSave = { id, size ->
                widgets = widgets + id
                QuickWidgetStore.saveSize(this, id, size)
                persistWidgets()
            },
            onDelete = { id ->
                widgets = widgets - id
                QuickWidgetStore.removeSize(this, id)
                persistWidgets()
            }
        ).also { it.show() }
    }

    /** Simpan ke SharedPreferences lalu render ulang Quick Launch. */
    private fun persistWidgets() {
        prefs.edit().putStringSet(KEY_WIDGETS, HashSet(widgets)).apply()
        pagerAdapter.setWidgets(widgets)
        binding.viewPager.adapter?.notifyItemChanged(QUICK_LAUNCH_PAGE)
        syncLightSensor()
    }

    private fun syncLightSensor() {
        sensorManager.unregisterListener(lightListener)
        if ("light_sensor" !in widgets) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) {
            pagerAdapter.setLightLux(null)
        } else {
            sensorManager.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    // ---------- Terminal ----------

    private fun setupTerminal() {
        binding.terminalRecycler.layoutManager = LinearLayoutManager(this)
        binding.terminalRecycler.adapter = terminalAdapter
        binding.terminalRecycler.itemAnimator = null
        terminalInputType = binding.terminalInput.inputType

        NexusBus.terminalPrinter = { text -> runOnUiThread { appendText(text) } }
        NexusBus.terminalClearer = { runOnUiThread { terminalAdapter.clear() } }
        NexusBus.matrixStart = {
            runOnUiThread {
                openTerminal(showKeyboard = false)
                binding.matrixRain.startMatrixRain()
            }
        }
        NexusBus.matrixStop = { runOnUiThread { binding.matrixRain.stopMatrixRain() } }

        appendLines(
            listOf(
                TerminalLine("NEXUS-CLI v2.0 — ketik 'help' untuk daftar perintah.", LineType.SYSTEM)
            )
        )
        binding.terminalPrompt.text = fs.prompt()

        binding.btnOpenTerminal.setOnClickListener { openTerminal(showKeyboard = true) }
        binding.btnCloseTerminal.setOnClickListener { closeTerminal() }

        binding.terminalInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                isEnter
            ) {
                submitInput()
                true
            } else {
                false
            }
        }
    }

    private fun openTerminal(showKeyboard: Boolean) {
        binding.terminalPanel.visibility = View.VISIBLE
        if (showKeyboard) {
            binding.terminalInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.terminalInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeTerminal() {
        binding.matrixRain.stopMatrixRain()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.terminalInput.windowToken, 0)
        binding.terminalInput.clearFocus()
        binding.terminalPanel.visibility = View.GONE
    }

    private fun submitInput() {
        val raw = binding.terminalInput.text.toString()
        val secret = shell.isAwaitingSecret
        binding.terminalInput.setText("")
        // Password tidak di-trim (spasi bisa bagian dari password).
        val text = if (secret) raw else raw.trim()
        if (text.isEmpty()) return
        runCommand(text, secret)
    }

    /** Dipakai input terminal dan perintah suara (non-media). */
    private fun runCommand(line: String, secret: Boolean = false) {
        // Password tidak pernah ditampilkan di terminal.
        val echo = if (secret) "********" else line
        appendLines(listOf(TerminalLine(fs.prompt() + echo, LineType.INPUT)))
        lifecycleScope.launch {
            val result = try {
                shell.execute(line)
            } catch (e: Exception) {
                ShellResult.error("shell: ${e.message ?: e.javaClass.simpleName}")
            }
            if (result.clear) terminalAdapter.clear()
            appendLines(result.lines)
            binding.terminalPrompt.text = fs.prompt()
            applyInputMode()
            if (result.exit) exitNexus()
        }
    }

    /** Saat menunggu password Wi-Fi, field input disamarkan. */
    private fun applyInputMode() {
        binding.terminalInput.inputType = if (shell.isAwaitingSecret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            terminalInputType
        }
    }

    private fun appendLines(lines: List<TerminalLine>) {
        if (lines.isEmpty()) return
        terminalAdapter.add(lines)
        binding.terminalRecycler.scrollToPosition(terminalAdapter.itemCount - 1)
        pagerAdapter.setLastTerminalLine(lines.last().text)
    }

    private fun appendText(text: String, type: LineType = LineType.OUTPUT) {
        appendLines(text.split("\n").map { TerminalLine(it, type) })
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.terminalPanel.visibility == View.VISIBLE -> closeTerminal()
                    binding.viewPager.currentItem != 0 -> binding.viewPager.currentItem = 0
                }
            }
        })
    }

    // ---------- NEXUS-NET ----------

    private fun requestLabPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        labPermissionLauncher.launch(perms.toTypedArray())
    }

    // ---------- Kiosk (hanya dilepas, tidak lagi dipaksa saat start) ----------

    private fun lockTaskState(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState
    }

    private fun releaseKiosk() {
        try {
            if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        } catch (_: Exception) {
        }
    }

    private fun exitNexus() {
        releaseKiosk()
        finishAffinity()
    }

    // ---------- Status bar loops ----------

    private fun startClockLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    binding.statusClock.text = LocalTime.now().format(clockFmt)
                    pagerAdapter.tick()
                    delay(1000)
                }
            }
        }
    }

    private fun startMetricsLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val snap = withContext(Dispatchers.Default) { monitor.sample() }
                    pagerAdapter.pushMetrics(snap)

                    binding.statusNetwork.text = "NET ${snap.netLabel}"
                    binding.statusBattery.text = if (snap.batteryPct >= 0) {
                        "BAT ${snap.batteryPct}%${if (snap.charging) "+" else ""}"
                    } else {
                        "BAT --"
                    }
                    val low = snap.batteryPct in 0..19 && !snap.charging
                    binding.statusBattery.setTextColor(
                        ContextCompat.getColor(this@MainActivity, if (low) R.color.nexus_red else R.color.nexus_green)
                    )
                    delay(1500)
                }
            }
        }
    }

    private fun startWeatherLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val w = weather.fetch().getOrNull()
                    if (w != null) {
                        pagerAdapter.setWeather(w, null)
                    } else {
                        pagerAdapter.setWeather(null, "Cuaca tidak tersedia (offline?)")
                    }
                    delay(if (w != null) 30 * 60 * 1000L else 60_000L)
                }
            }
        }
    }

    private fun startMediaCollector() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaRepository.state.collect { pagerAdapter.setMedia(it) }
            }
        }
    }

    // ---------- PageActions ----------

    override fun onTileClick(tile: QuickTile) {
        when (tile.id) {
            "terminal" -> openTerminal(showKeyboard = true)
            "vscode" -> launchApp("VS Code", "com.microsoft.vscode", fallbackUrl = "https://vscode.dev")
            "browser" -> launchApp(
                "Browser", "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
                "com.sec.android.app.sbrowser", fallbackUrl = "https://www.google.com"
            )
            "files" -> launchApp(
                "Files", "com.google.android.apps.nbu.files", "com.sec.android.app.myfiles",
                "com.android.documentsui", "com.mi.android.globalFileexplorer"
            )
            "spotify" -> launchApp("Spotify", "com.spotify.music")
            "discord" -> launchApp("Discord", "com.discord")
            "ytmusic" -> launchApp("YT Music", "com.google.android.apps.youtube.music")
            "camera" -> {
                val ok = apps.launchFirstInstalled(
                    "com.google.android.GoogleCamera", "com.sec.android.app.camera",
                    "com.android.camera2", "com.android.camera"
                ) || apps.start(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                if (!ok) toast("Camera tidak ditemukan")
            }
            "calc" -> launchApp(
                "Calculator", "com.google.android.calculator", "com.sec.android.app.popupcalculator",
                "com.android.calculator2"
            )
            "clock" -> launchApp(
                "Clock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
                "com.android.deskclock"
            )
            "mute" -> {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            "settings" -> if (!apps.start(Intent(Settings.ACTION_SETTINGS))) toast("Settings tidak bisa dibuka")
        }
    }

    override fun onMediaPrev() = MediaRepository.previous()

    /** Tombol Play/Pause: HANYA play/pause di tempat. Tidak membuka aplikasi musik. */
    override fun onMediaPlayPause() = MediaRepository.playPause()

    override fun onMediaNext() = MediaRepository.next()

    /**
     * Dipasang adapter HANYA di vinylDisc / albumArt.
     * Tanpa akses notifikasi -> buka pengaturan akses; selain itu -> buka aplikasi musik.
     */
    override fun onMediaCardClick() {
        if (!MediaRepository.state.value.hasAccess) {
            if (!apps.start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                toast("Tidak bisa membuka pengaturan akses notifikasi")
            }
            return
        }
        launchApp("Aplikasi musik", "com.spotify.music", "com.google.android.apps.youtube.music")
    }

    private fun launchApp(label: String, vararg packages: String, fallbackUrl: String? = null) {
        val ok = apps.launchFirstInstalled(*packages) || (fallbackUrl != null && apps.openUrl(fallbackUrl))
        if (!ok) toast("$label tidak ditemukan")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------- Mic ----------

    private fun setupMic() {
        NexusBus.micToggle = { enable -> setMic(enable) }

        val filter = IntentFilter().apply {
            addAction(VoiceForegroundService.ACTION_MIC_OFF)
            addAction(VoiceForegroundService.ACTION_VOICE_HEARD)
            addAction(VoiceForegroundService.ACTION_VOICE_COMMAND)
        }
        ContextCompat.registerReceiver(this, voiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        binding.btnMicToggle.setOnClickListener {
            setMic(!prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false))
        }

        // Fallback boot: jika mic terakhir menyala, hidupkan lagi saat launcher terbuka.
        if (prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false) && hasMicPermission()) {
            startVoiceService()
        }
        refreshMicIcon()
    }

    private fun setMic(enable: Boolean) {
        if (enable) {
            if (hasMicPermission()) {
                startVoiceService()
                setMicState(true)
                appendText("[voice] mikrofon aktif. Ucapkan \"Hey Nexus, <perintah>\".", LineType.SYSTEM)
            } else {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
        } else {
            stopService(Intent(this, VoiceForegroundService::class.java))
            setMicState(false)
            appendText("[voice] mikrofon nonaktif.", LineType.SYSTEM)
        }
    }

    private fun startVoiceService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, VoiceForegroundService::class.java))
        } catch (e: Exception) {
            setMicState(false)
            appendText("[voice] gagal menjalankan service: ${e.message}", LineType.ERROR)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun setMicState(enabled: Boolean) {
        prefs.edit().putBoolean(VoiceForegroundService.KEY_MIC_ENABLED, enabled).apply()
        refreshMicIcon()
    }

    private fun refreshMicIcon() {
        val on = prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false)
        binding.btnMicToggle.setImageResource(if (on) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
    }

    private companion object {
        const val KEY_WIDGETS = "quick_widgets"
        const val QUICK_LAUNCH_PAGE = 1
    }
}

// ============================================================================
// NexusUi: helper yang dipanggil NexusPagerAdapter (bug disc, progress, long press 5 detik)
// ============================================================================

object NexusUi {

    const val PROGRESS_MAX = 10_000
    const val QUICK_LONG_PRESS_MS = 5_000L

    private val progressAnims = WeakHashMap<View, ObjectAnimator>()
    private val vinylAnims = WeakHashMap<View, ObjectAnimator>()

    /** Disc harus bulat: ShapeableImageView ATAU ImageView biasa. */
    fun makeCircular(view: View) {
        if (view is ShapeableImageView) {
            view.shapeAppearanceModel = view.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        view.clipToOutline = true
    }

    /** Progress bergerak halus dari posisi sekarang sampai akhir lagu (ObjectAnimator pada "progress"). */
    fun animateProgress(bar: ProgressBar, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        progressAnims.remove(bar)?.cancel()
        bar.max = PROGRESS_MAX
        val pos = if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else 0L
        val from = if (durationMs > 0) ((pos * PROGRESS_MAX) / durationMs).toInt() else 0
        bar.progress = from
        if (!isPlaying || durationMs <= 0) return
        progressAnims[bar] = ObjectAnimator.ofInt(bar, "progress", from, PROGRESS_MAX).apply {
            duration = (durationMs - pos).coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Putar/jeda vinyl tanpa reset sudut. Hanya disc yang berputar, album art diam. */
    fun spinVinyl(disc: View, playing: Boolean) {
        val anim = vinylAnims.getOrPut(disc) {
            ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).apply {
                duration = 4_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        if (playing) {
            if (anim.isPaused) anim.resume() else if (!anim.isStarted) anim.start()
        } else if (anim.isRunning) {
            anim.pause()
        }
    }

    /**
     * Pemicu HANYA bila ditahan [holdMs] tanpa bergeser; swipe/scroll (ACTION_CANCEL / geser > slop)
     * membatalkan pemicu. Setelah terpicu, UP dikonsumsi agar widget tidak ikut ter-klik.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachLongPress(target: View, holdMs: Long = QUICK_LONG_PRESS_MS, onTrigger: () -> Unit) {
        val slop = ViewConfiguration.get(target.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var fired = false
        val trigger = Runnable {
            fired = true
            target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTrigger()
        }
        target.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    downX = e.rawX
                    downY = e.rawY
                    target.postDelayed(trigger, holdMs)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(e.rawX - downX, e.rawY - downY) > slop) target.removeCallbacks(trigger)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.removeCallbacks(trigger)
            }
            fired && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
        }
        target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(trigger)
            }
        })
    }
}

// ============================================================================
// Ukuran widget Quick Launch (tersimpan di SharedPreferences yang sama dengan set widget)
// ============================================================================

enum class WidgetBoxSize(val key: String, val cols: Int, val rows: Int) {
    S1X1("1x1", 1, 1),
    S2X1("2x1", 2, 1),
    S1X2("1x2", 1, 2),
    S2X2("2x2", 2, 2),
    S4X1("4x1", 4, 1);

    companion object {
        fun fromKey(key: String?): WidgetBoxSize? = entries.firstOrNull { it.key == key }
    }
}

object QuickWidgetStore {
    private const val KEY_SIZES = "quick_widget_sizes"

    private fun prefs(context: Context) =
        context.getSharedPreferences(VoiceForegroundService.PREFS_NAME, Context.MODE_PRIVATE)

    fun sizes(context: Context): Map<String, WidgetBoxSize> {
        val raw = prefs(context).getString(KEY_SIZES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence()
                .mapNotNull { id -> WidgetBoxSize.fromKey(obj.optString(id))?.let { id to it } }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun sizeOf(context: Context, id: String): WidgetBoxSize =
        sizes(context)[id] ?: WidgetBoxSize.S1X1

    fun saveSize(context: Context, id: String, size: WidgetBoxSize) {
        persist(context, sizes(context).toMutableMap().apply { put(id, size) })
    }

    fun removeSize(context: Context, id: String) {
        persist(context, sizes(context).toMutableMap().apply { remove(id) })
    }

    private fun persist(context: Context, map: Map<String, WidgetBoxSize>) {
        val obj = JSONObject()
        map.forEach { (id, size) -> obj.put(id, size.key) }
        prefs(context).edit().putString(KEY_SIZES, obj.toString()).apply()
    }
}

// ============================================================================
// BottomSheet kustomisasi widget (glassmorphism, ikon berwarna, preview, ukuran, hapus)
// ============================================================================

class QuickWidgetEditorSheet(
    private val context: Context,
    private val candidates: List<String>,
    private val active: Set<String>,
    sizes: Map<String, WidgetBoxSize>,
    private val onSave: (String, WidgetBoxSize) -> Unit,
    private val onDelete: (String) -> Unit
) {

    private val work = HashMap<String, WidgetBoxSize>(sizes)
    private var selId: String = candidates.firstOrNull { it in active } ?: candidates.first()
    private var dialog: BottomSheetDialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun dismiss() {
        dialog?.dismiss()
    }

    fun show() {
        val neon = Color.parseColor("#00FF66")

        val cardRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val sizeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val previewHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100))
        }
        val btnSave = actionButton("Simpan", neon, Color.BLACK, neon)
        val btnDelete = actionButton(
            "Hapus Widget", 0x00000000, Color.parseColor("#FF5555"), Color.parseColor("#FF5555")
        )
        val btnCancel = actionButton("Batal", 0x00000000, Color.parseColor("#B0B0BA"), 0x33FFFFFF)

        fun render() {
            cardRow.removeAllViews()
            candidates.forEachIndexed { index, id ->
                cardRow.addView(widgetCard(id, index, id == selId, id in active) {
                    selId = id
                    render()
                })
            }
            val current = work[selId] ?: WidgetBoxSize.S1X1
            sizeRow.removeAllViews()
            WidgetBoxSize.entries.forEach { s ->
                sizeRow.addView(sizeChip(s, s == current) {
                    work[selId] = s
                    render()
                })
            }
            previewHost.removeAllViews()
            previewHost.addView(buildPreview(selId, candidates.indexOf(selId), current))
            btnDelete.alpha = if (selId in active) 1f else 0.4f
        }

        val dlg = BottomSheetDialog(context)
        dialog = dlg

        btnSave.setOnClickListener {
            onSave(selId, work[selId] ?: WidgetBoxSize.S1X1)
            dlg.dismiss()
            Toast.makeText(context, "Widget disimpan: ${labelOf(selId)}", Toast.LENGTH_SHORT).show()
        }
        btnDelete.setOnClickListener {
            if (selId in active) {
                onDelete(selId)
                dlg.dismiss()
                Toast.makeText(context, "Widget dihapus: ${labelOf(selId)}", Toast.LENGTH_SHORT).show()
            }
        }
        btnCancel.setOnClickListener { dlg.dismiss() }

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
            addView(sectionLabel("Pilih widget"))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(cardRow)
            })
            addView(sectionLabel("Ukuran"))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(sizeRow)
            })
        }

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(20)
            }
            addView(sectionLabel("Preview"))
            addView(previewHost)
            addView(btnSave)
            addView(btnDelete)
            addView(btnCancel)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = glassSheetBackground()
            addView(TextView(context).apply {
                text = "Kustomisasi Quick Launch"
                setTextColor(neon)
                textSize = 16f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(left)
                addView(right)
            })
        }

        render()
        dlg.setContentView(ScrollView(context).apply { addView(root) })
        dlg.setOnShowListener {
            dlg.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dlg.behavior.skipCollapsed = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dlg.window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = 32 }
            }
        }
        dlg.show()
    }

    // ---------- helper UI ----------

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun labelOf(id: String): String =
        id.split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private fun emojiOf(id: String): String = when {
        "light" in id -> "💡"
        "clock" in id || "time" in id -> "🕒"
        "cal" in id -> "📅"
        "weather" in id -> "☁️"
        "media" in id || "music" in id -> "🎵"
        "cpu" in id || "chart" in id -> "📈"
        "ram" in id || "mem" in id -> "🧠"
        "batt" in id -> "🔋"
        "net" in id -> "📡"
        "term" in id -> "💻"
        else -> "🧩"
    }

    private fun colorOf(index: Int): Int =
        Color.parseColor(PALETTE[index.coerceAtLeast(0) % PALETTE.size])

    private fun roundedBg(fill: Int, stroke: Int, strokeDp: Int = 1, radiusDp: Int = 14) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(strokeDp), stroke)
        }

    // TODO: pindahkan ke drawable bg_glass_card.xml
    private fun glassSheetBackground(): GradientDrawable {
        val r = dp(24).toFloat()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#B31E1E26"), Color.parseColor("#CC0A0A0C"))
        ).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(1), Color.parseColor("#3300FF66"))
        }
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#8A8A94"))
        textSize = 11f
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun widgetCard(
        id: String,
        index: Int,
        selected: Boolean,
        isActive: Boolean,
        onClick: () -> Unit
    ): View {
        val c = colorOf(index)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = if (isActive) 1f else 0.55f
            layoutParams = LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = roundedBg(
                if (selected) ColorUtils.setAlphaComponent(c, 60) else 0x14FFFFFF,
                if (selected) c else 0x22FFFFFF,
                if (selected) 2 else 1
            )
            addView(TextView(context).apply {
                text = emojiOf(id)
                textSize = 22f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = roundedBg(ColorUtils.setAlphaComponent(c, 70), c, 1, 12)
            })
            addView(TextView(context).apply {
                text = labelOf(id)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(context).apply {
                text = if (isActive) "aktif" else "nonaktif"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(if (isActive) Color.parseColor("#00FF66") else Color.parseColor("#8A8A94"))
            })
            setOnClickListener { onClick() }
        }
    }

    private fun sizeChip(s: WidgetBoxSize, selected: Boolean, onClick: () -> Unit): View {
        val neon = Color.parseColor("#00FF66")
        return TextView(context).apply {
            text = s.key
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.BLACK else Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = roundedBg(
                if (selected) neon else 0x14FFFFFF,
                if (selected) neon else 0x33FFFFFF,
                1, 10
            )
            setOnClickListener { onClick() }
        }
    }

    private fun buildPreview(id: String, index: Int, s: WidgetBoxSize): View {
        val c = colorOf(index)
        val unit = dp(40)
        val gap = dp(6)
        val w = s.cols * unit + (s.cols - 1) * gap
        val h = s.rows * unit + (s.rows - 1) * gap
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            background = roundedBg(ColorUtils.setAlphaComponent(c, 50), c, 1, 14)
            addView(TextView(context).apply {
                text = emojiOf(id)
                textSize = if (s == WidgetBoxSize.S1X1) 18f else 26f
                gravity = Gravity.CENTER
            })
            if (s != WidgetBoxSize.S1X1) {
                addView(TextView(context).apply {
                    text = labelOf(id)
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
            }
        }
    }

    private fun actionButton(label: String, fill: Int, textColor: Int, stroke: Int) =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            background = roundedBg(fill, stroke, 1, 12)
        }

    private companion object {
        val PALETTE = listOf("#00FF66", "#4DA3FF", "#FF5DA2", "#FFB84D", "#B388FF", "#7FD8FF", "#FFE66D")
    }
}