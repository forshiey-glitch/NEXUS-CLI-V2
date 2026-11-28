package com.nexus.cli

/*
 * ============================ CATATAN XML (WAJIB) ============================
 * 1) res/layout/page_dashboard.xml  (Halaman 1 - Bento Dashboard)
 *    vinylDisc = ShapeableImageView DENGAN app:srcCompat + scaleType + overlay bulat (@style/Nexus.Circle,
 *    ada di res/values/styles_nexus_shapes.xml). NexusUi.prepareVinyl() memuat ulang drawable dan
 *    memaksa bulat TANPA menimpa outline ShapeableImageView, sehingga gambar tidak hilang.
 * 2) mediaProgress: LinearProgressIndicator dengan android:max="10000"; digerakkan ObjectAnimator
 *    (NexusUi.animateProgress).
 * 3) res/layout/page_terminal.xml (Halaman 2): terminalRecycler, terminalPrompt, terminalInput,
 *    btnCloseTerminal, plus timer sesi (sessionTimer, sessionState, btnSessionToggle, btnSessionReset,
 *    sessionTotalToday).
 * 4) Klik buka-app-musik HANYA di vinylDisc/albumArt -> onMediaCardClick(); btnPlayPause -> onMediaPlayPause().
 * =============================================================================
 */

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup // NEXUS-DRAWER
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize // NEXUS-DRAWER
import androidx.compose.runtime.Composable // NEXUS-DRAWER
import androidx.compose.ui.Modifier // NEXUS-DRAWER
import androidx.compose.ui.platform.ComposeView // NEXUS-DRAWER
import androidx.compose.ui.viewinterop.AndroidView // NEXUS-DRAWER
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider // NEXUS-DRAWER
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.nexus.cli.databinding.ActivityMainBinding
import com.nexus.cli.nexusdrawer.NexusDrawerViewModel // NEXUS-DRAWER
import com.nexus.cli.nexusdrawer.NexusShell // NEXUS-DRAWER
import com.nexus.cli.nexusnet.NexusNetPresenter
import com.nexus.cli.nexusnet.WifiLiveScanner
import com.nexus.cli.nexusnet.WifiTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap

class MainActivity : AppCompatActivity(), PageActions {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private lateinit var fs: VirtualFS
    private lateinit var apps: AppRepository
    private lateinit var weather: WeatherRepository
    private lateinit var monitor: SystemMonitor
    private lateinit var shell: ShellEngine
    private lateinit var wifiScanner: WifiLiveScanner
    private lateinit var pagerAdapter: NexusPagerAdapter
    private lateinit var nexusVm: NexusDrawerViewModel // NEXUS-DRAWER
    private val terminalAdapter = TerminalAdapter()

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

    // View terminal (Halaman 2) dibuat oleh adapter; null sampai halaman itu dibuat.
    private var terminalViews: TerminalViews? = null

    // Input terminal asli (dipulihkan setelah mode password)
    private var terminalInputType = InputType.TYPE_CLASS_TEXT
    private var terminalInputTypeCaptured = false

    // Cek launcher default (sekali per proses)
    private var launcherPromptShown = false

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
                        // Tidak memindahkan halaman: perintah suara berjalan di latar belakang.
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

    // Izin lokasi dibutuhkan Android agar nama Wi-Fi (SSID) bisa dibaca di status bar dan panel Nexus-Net.
    private val ssidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun askLocationForSsidOnce() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (granted || prefs.getBoolean(KEY_ASKED_LOCATION, false)) return
        prefs.edit().putBoolean(KEY_ASKED_LOCATION, true).apply()
        ssidPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        // NEXUS-DRAWER: UI lama dibungkus NexusShell (overlay), isinya tidak diubah.
        nexusVm = ViewModelProvider(this)[NexusDrawerViewModel::class.java]
        setContentView(createNexusRoot())

        prefs = getSharedPreferences(VoiceForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
        MediaRepository.attach(this)

        fs = VirtualFS(this)
        apps = AppRepository(this) { releaseKiosk() }
        weather = WeatherRepository()
        monitor = SystemMonitor(this)
        wifiScanner = WifiLiveScanner(this)
        shell = ShellEngine(
            context = this,
            registry = CommandRegistry(),
            shellContext = ShellContext(applicationContext, fs, apps, weather, monitor),
            cpuProvider = { monitor.getCpuUsage() },
            presence = nexusVm // NEXUS-DRAWER
        )
        // Callback dipanggil dari thread background -> wajib pindah ke main thread.
        shell.nexusNet.onNeedLocationPermission = { runOnUiThread { requestLabPermissions() } }

        setupPager()
        setupTerminalBus()
        setupMic()
        setupBackHandler()
        askLocationForSsidOnce()

        startClockLoop()
        startMetricsLoop()
        startWeatherLoop()
        startMediaCollector()
        startWifiLoop()
    }

    // ---------- NEXUS-DRAWER ----------

    private fun createNexusRoot(): ComposeView = ComposeView(this).apply {
        setContent {
            NexusShell(
                viewModel = nexusVm,
                onMicClick = { setMic(!prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false)) },
                onKeyboardClick = {
                    nexusVm.close()
                    openTerminal(showKeyboard = true)
                },
                onSettingsClick = {
                    nexusVm.close()
                    if (!apps.start(Intent(Settings.ACTION_SETTINGS))) toast("Pengaturan tidak bisa dibuka")
                }
            ) { ExistingContent() }
        }
    }

    /** UI lama (status bar + ViewPager2 Dashboard/Terminal) apa adanya. */
    @Composable
    private fun ExistingContent() {
        AndroidView(
            factory = {
                (binding.root.parent as? ViewGroup)?.removeView(binding.root)
                binding.root
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    override fun onResume() {
        super.onResume()
        MediaRepository.refresh(this)
        refreshMicIcon()
        promptDefaultLauncherIfNeeded()
    }

    override fun onDestroy() {
        NexusBus.terminalPrinter = null
        NexusBus.terminalClearer = null
        NexusBus.micToggle = null
        monitor.close()
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

    // ---------- Pager (2 halaman: Dashboard, Terminal) ----------

    private fun setupPager() {
        pagerAdapter = NexusPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        // Dengan 2 halaman, limit 1 menjaga kedua halaman tetap hidup (terminal tidak dibuat ulang).
        binding.viewPager.offscreenPageLimit = 1
        buildPageIndicator(pagerAdapter.itemCount)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                if (position != NexusPagerAdapter.PAGE_TERMINAL) hideKeyboard()
            }
        })
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

    // ---------- Terminal (Halaman 2) ----------

    private fun setupTerminalBus() {
        NexusBus.terminalPrinter = { text -> runOnUiThread { appendText(text) } }
        NexusBus.terminalClearer = { runOnUiThread { terminalAdapter.clear() } }

        terminalAdapter.add(
            listOf(TerminalLine("NEXUS-CLI v2.0 — ketik 'help' untuk daftar perintah.", LineType.SYSTEM))
        )

        binding.btnOpenTerminal.setOnClickListener { openTerminal(showKeyboard = true) }
    }

    /** Dipanggil adapter setiap kali halaman terminal dibuat (idempotent). */
    override fun onTerminalReady(views: TerminalViews) {
        terminalViews = views
        if (!terminalInputTypeCaptured) {
            terminalInputType = views.input.inputType
            terminalInputTypeCaptured = true
        }
        views.recycler.layoutManager = LinearLayoutManager(this)
        views.recycler.adapter = terminalAdapter
        views.recycler.itemAnimator = null
        views.prompt.text = fs.prompt()

        views.input.setOnEditorActionListener { _, actionId, event ->
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
        applyInputMode()
        if (terminalAdapter.itemCount > 0) views.recycler.scrollToPosition(terminalAdapter.itemCount - 1)
    }

    override fun onTerminalClose() = closeTerminal()

    private fun openTerminal(showKeyboard: Boolean) {
        binding.viewPager.setCurrentItem(NexusPagerAdapter.PAGE_TERMINAL, true)
        if (showKeyboard) {
            binding.viewPager.post {
                val input = terminalViews?.input ?: return@post
                input.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeTerminal() {
        hideKeyboard()
        terminalViews?.input?.clearFocus()
        binding.viewPager.setCurrentItem(NexusPagerAdapter.PAGE_DASHBOARD, true)
    }

    private fun hideKeyboard() {
        val token = terminalViews?.input?.windowToken ?: binding.root.windowToken ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun submitInput() {
        val input = terminalViews?.input ?: return
        val raw = input.text.toString()
        val secret = shell.isAwaitingSecret
        input.setText("")
        // Password tidak di-trim (spasi bisa bagian dari password).
        val text = if (secret) raw else raw.trim()
        if (text.isEmpty()) return
        runCommand(text, secret)
    }

    /** Dipakai input terminal, perintah suara (non-media), dan pilihan jaringan di Nexus-Net Panel. */
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
            terminalViews?.prompt?.text = fs.prompt()
            applyInputMode()
            if (result.exit) exitNexus()
        }
    }

    /** Saat menunggu password Wi-Fi, field input disamarkan. */
    private fun applyInputMode() {
        val input = terminalViews?.input ?: return
        input.inputType = if (shell.isAwaitingSecret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            terminalInputType
        }
    }

    private fun appendLines(lines: List<TerminalLine>) {
        if (lines.isEmpty()) return
        terminalAdapter.add(lines)
        terminalViews?.recycler?.scrollToPosition(terminalAdapter.itemCount - 1)
    }

    private fun appendText(text: String, type: LineType = LineType.OUTPUT) {
        appendLines(text.split("\n").map { TerminalLine(it, type) })
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // NEXUS-DRAWER: back menutup drawer lebih dulu.
                if (nexusVm.isOpen.value) {
                    nexusVm.close()
                    return
                }
                if (binding.viewPager.currentItem != NexusPagerAdapter.PAGE_DASHBOARD) closeTerminal()
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

    /**
     * Jaringan diketuk di Nexus-Net Panel: HANYA memilih target lalu memulai `nexus-net connect`.
     * Jaringan terbuka langsung tersambung; jaringan berpassword meminta password yang DIKETIK MANUAL
     * di terminal (tidak disimpan, tidak ada tebak-tebakan password).
     */
    override fun onWifiSelected(target: WifiTarget) {
        if (shell.isAwaitingSecret) {
            openTerminal(showKeyboard = true)
            appendText(
                "[nexus-net] masih menunggu password jaringan sebelumnya. Ketik password atau 'cancel'.",
                LineType.SYSTEM
            )
            return
        }
        NexusNetPresenter.selectTarget(target)
        openTerminal(showKeyboard = true)
        appendText("[nexus-net] target dipilih: ${NexusNetPresenter.label(target)}", LineType.SYSTEM)
        runCommand("nexus-net connect")
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

    // ---------- Loops ----------

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

    /** Status bar atas: baterai + jaringan (Wi-Fi SSID / CELL / OFFLINE lewat ConnectivityManager di SystemMonitor). */
    private fun startMetricsLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val snap = withContext(Dispatchers.Default) { monitor.sample() }

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

    /**
     * Coroutine timer Nexus-Net Panel: baca daftar Wi-Fi tiap [WIFI_REFRESH_MS] (1 detik).
     * Hanya berjalan saat Dashboard terlihat. Hanya SCAN/daftar, tanpa uji password.
     */
    private fun startWifiLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val startedAt = SystemClock.elapsedRealtime()
                    if (binding.viewPager.currentItem == NexusPagerAdapter.PAGE_DASHBOARD) {
                        val snap = withContext(Dispatchers.Default) { wifiScanner.poll() }
                        pagerAdapter.setWifi(snap)
                    }
                    val spent = SystemClock.elapsedRealtime() - startedAt
                    delay((WIFI_REFRESH_MS - spent).coerceAtLeast(100L))
                }
            }
        }
    }

    // ---------- PageActions (media) ----------

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
        val ok = MediaRepository.launchMostUsedMusic { pkg -> apps.launchPackage(pkg) }
        if (!ok) launchApp("Aplikasi musik", "com.spotify.music", "com.google.android.apps.youtube.music")
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
        nexusVm.setListening(prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false)) // NEXUS-DRAWER
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
        nexusVm.setListening(enabled) // NEXUS-DRAWER
    }

    private fun refreshMicIcon() {
        val on = prefs.getBoolean(VoiceForegroundService.KEY_MIC_ENABLED, false)
        binding.btnMicToggle.setImageResource(if (on) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
    }

    private companion object {
        const val KEY_ASKED_LOCATION = "asked_location_ssid"

        /** Interval refresh Nexus-Net Panel (ms). */
        const val WIFI_REFRESH_MS = 1_000L
    }
}

// ============================================================================
// NexusUi: helper yang dipanggil NexusPagerAdapter
// ============================================================================

object NexusUi {

    const val PROGRESS_MAX = 10_000

    private val progressAnims = WeakHashMap<View, ObjectAnimator>()

    /**
     * Pastikan piringan terlihat DAN bulat.
     * - Muat ulang drawable (mencegah gambar kosong saat tipe view berganti ke ShapeableImageView).
     * - ShapeableImageView: cukup shapeAppearance bulat; JANGAN menimpa outlineProvider-nya
     *   (itu yang membuat gambar hilang). ImageView biasa: pakai outline oval.
     */
    fun prepareVinyl(view: ImageView, @DrawableRes drawableRes: Int) {
        view.setImageResource(drawableRes)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.alpha = 1f
        view.visibility = View.VISIBLE
        applyCircle(view)
    }

    /** Dipakai juga untuk albumArt (bitmap bisa persegi). */
    fun applyCircle(view: ImageView) {
        if (view is ShapeableImageView) {
            view.shapeAppearanceModel = view.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
        } else {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            view.clipToOutline = true
        }
    }

    /**
     * Progress bergerak halus: ObjectAnimator linear pada "progress" dari posisi sekarang
     * sampai akhir lagu (bukan melompat tiap detik). [speed] = kecepatan putar media.
     */
    fun animateProgress(
        bar: ProgressBar,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        speed: Float = 1f
    ) {
        cancelProgress(bar)
        bar.max = PROGRESS_MAX
        val pos = if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else 0L
        val from = if (durationMs > 0) ((pos * PROGRESS_MAX) / durationMs).toInt() else 0
        bar.progress = from
        if (!isPlaying || durationMs <= 0) return
        val safeSpeed = if (speed > 0f) speed else 1f
        progressAnims[bar] = ObjectAnimator.ofInt(bar, "progress", from, PROGRESS_MAX).apply {
            duration = ((durationMs - pos) / safeSpeed).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun cancelProgress(bar: ProgressBar) {
        progressAnims.remove(bar)?.cancel()
    }
}