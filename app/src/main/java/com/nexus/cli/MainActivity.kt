package com.nexus.cli

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nexus.cli.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

    // Kustomisasi Quick Launch
    private var widgets: Set<String> = QuickWidgets.default
    private var widgetSheet: WidgetCustomizeSheet? = null

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
                    if (cmd.isNotBlank()) {
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

    override fun onQuickLaunchLongPress() {
        if (widgetSheet?.isShowing == true) return
        widgetSheet = WidgetCustomizeSheet(this, widgets) { chosen ->
            widgets = chosen
            prefs.edit().putStringSet(KEY_WIDGETS, HashSet(chosen)).apply()
            pagerAdapter.setWidgets(chosen)
            syncLightSensor()
        }.also { it.show() }
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
        val text = binding.terminalInput.text.toString().trim()
        binding.terminalInput.setText("")
        if (text.isEmpty()) return
        runCommand(text)
    }

    /** Dipakai input terminal dan perintah suara (non-media). */
    private fun runCommand(line: String) {
        appendLines(listOf(TerminalLine(fs.prompt() + line, LineType.INPUT)))
        lifecycleScope.launch {
            val result = try {
                shell.execute(line)
            } catch (e: Exception) {
                ShellResult.error("shell: ${e.message ?: e.javaClass.simpleName}")
            }
            if (result.clear) terminalAdapter.clear()
            appendLines(result.lines)
            binding.terminalPrompt.text = fs.prompt()
            if (result.exit) exitNexus()
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

    /**
     * Tombol Play: jika sedang main -> pause. Jika tidak -> buka aplikasi musik yang paling sering
     * dibuka + kirim Media Button Play. TIDAK memindahkan halaman ViewPager.
     */
    override fun onMediaPlayPause() = MediaRepository.onPlayPressed { pkg -> apps.launchPackage(pkg) }

    override fun onMediaNext() = MediaRepository.next()

    override fun onMediaCardClick() {
        if (!MediaRepository.state.value.hasAccess) {
            if (!apps.start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                toast("Tidak bisa membuka pengaturan akses notifikasi")
            }
        }
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
    }
}