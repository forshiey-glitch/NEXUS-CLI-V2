package com.nexus.cli

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.nexus.cli.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(), PageActions {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: NexusPagerAdapter
    private lateinit var terminalAdapter: TerminalAdapter
    private lateinit var shell: ShellEngine
    private lateinit var fs: VirtualFS
    private lateinit var apps: AppRepository
    private lateinit var monitor: SystemMonitor

    private val weatherRepo = WeatherRepository()
    private val dots = mutableListOf<View>()

    private var bannerShown = false
    private var lastWeather: WeatherInfo? = null

    private val terminalOpen: Boolean
        get() = binding.terminalPanel.visibility == View.VISIBLE

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Paksa landscape lewat kode (selain sensorLandscape di manifest)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fs = VirtualFS(applicationContext)
        apps = AppRepository(applicationContext) { releaseKioskForLaunch() }
        monitor = SystemMonitor(applicationContext)
        shell = ShellEngine(ShellContext(applicationContext, fs, apps, weatherRepo, monitor))

        setupPager()
        setupTerminal()
        setupBackHandler()
        startLoops()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tombol Home ditekan saat NEXUS-CLI sudah jadi launcher utama
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (terminalOpen) closeTerminal()
            binding.viewPager.setCurrentItem(0, true)
        }
    }

    override fun onResume() {
        super.onResume()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        MediaRepository.refresh(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- pager ----------

    private fun setupPager() {
        pagerAdapter = NexusPagerAdapter(this)
        binding.viewPager.apply {
            adapter = pagerAdapter
            offscreenPageLimit = 3
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                }
            })
        }
        buildDots()
        updateDots(0)
    }

    private fun buildDots() {
        binding.pageIndicator.removeAllViews()
        dots.clear()
        for (i in 0 until PAGE_COUNT) {
            val dot = View(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dot)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                }
            }
            binding.pageIndicator.addView(dot)
            dots.add(dot)
        }
    }

    private fun updateDots(selected: Int) {
        dots.forEachIndexed { i, dot ->
            dot.alpha = if (i == selected) 1f else 0.3f
            dot.scaleX = if (i == selected) 1.4f else 1f
            dot.scaleY = if (i == selected) 1.4f else 1f
        }
    }

    // ---------- terminal ----------

    private fun setupTerminal() {
        terminalAdapter = TerminalAdapter()
        binding.terminalRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = terminalAdapter
            itemAnimator = null
        }

        binding.btnOpenTerminal.setOnClickListener { openTerminal() }
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
        updatePrompt()
    }

    private fun openTerminal() {
        binding.terminalPanel.visibility = View.VISIBLE
        if (!bannerShown) {
            bannerShown = true
            val lines = mutableListOf(
                TerminalLine("NEXUS-CLI v2.0 — Terminal-first launcher", LineType.SYSTEM),
                TerminalLine("Ketik 'help' untuk daftar perintah.", LineType.SYSTEM)
            )
            if (!MediaRepository.isAccessGranted(this)) {
                lines.add(
                    TerminalLine(
                        "Izin notifikasi belum aktif: ketuk kartu Media di Halaman 1 untuk membuka pengaturan.",
                        LineType.SYSTEM
                    )
                )
            }
            terminalAdapter.add(lines)
            scrollTerminal()
        }
        binding.terminalInput.requestFocus()
        WindowCompat.getInsetsController(window, binding.terminalInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeTerminal() {
        WindowCompat.getInsetsController(window, binding.terminalInput).hide(WindowInsetsCompat.Type.ime())
        binding.terminalPanel.visibility = View.GONE
        hideSystemBars()
    }

    private fun submitInput() {
        val text = binding.terminalInput.text.toString()
        binding.terminalInput.setText("")
        runCommand(text)
    }

    private fun runCommand(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return

        terminalAdapter.add(listOf(TerminalLine("${fs.prompt()}$input", LineType.INPUT)))
        scrollTerminal()

        lifecycleScope.launch {
            val result = shell.execute(input)
            if (result.clear) terminalAdapter.clear()
            terminalAdapter.add(result.lines)
            updatePrompt()
            scrollTerminal()
            if (result.exit) exitNexus()
        }
    }

    private fun updatePrompt() {
        binding.terminalPrompt.text = fs.prompt()
    }

    private fun scrollTerminal() {
        val last = terminalAdapter.itemCount - 1
        if (last >= 0) binding.terminalRecycler.scrollToPosition(last)
    }

    private fun addSystemLine(text: String) {
        terminalAdapter.add(listOf(TerminalLine(text, LineType.SYSTEM)))
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (terminalOpen) {
                    closeTerminal()
                } else if (binding.viewPager.currentItem != 0) {
                    binding.viewPager.currentItem = 0
                }
                // Launcher: di halaman 1 tombol back diabaikan.
            }
        })
    }

    // ---------- system bars & kiosk ----------

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Lock Task (screen pinning) tidak lagi aktif otomatis; ini hanya melepasnya jika sedang aktif. */
    private fun releaseKioskForLaunch() {
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                stopLockTask()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    private fun exitNexus() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            // abaikan
        }
        finishAndRemoveTask()
    }

    // ---------- loops: monitor, media, cuaca ----------

    private fun startLoops() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val snap = withContext(Dispatchers.Default) { monitor.sample() }
                    pagerAdapter.pushMetrics(snap)
                    pagerAdapter.tick()
                    updateStatusBar(snap)
                    delay(1000L)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaRepository.state.collect { pagerAdapter.setMedia(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    loadWeather()
                    delay(WEATHER_REFRESH_MS)
                }
            }
        }
    }

    private suspend fun loadWeather() {
        weatherRepo.fetch().fold(
            onSuccess = {
                lastWeather = it
                pagerAdapter.setWeather(it, null)
            },
            onFailure = {
                val cached = lastWeather
                pagerAdapter.setWeather(cached, if (cached == null) "Cuaca gagal dimuat (offline?)" else null)
            }
        )
    }

    private fun updateStatusBar(s: MetricsSnapshot) {
        binding.statusClock.text = LocalTime.now().format(TIME_FMT)
        binding.statusNetwork.text = "NET ${s.netLabel}"

        val batteryText = if (s.batteryPct >= 0) {
            "BAT ${s.batteryPct}%" + (if (s.charging) " CHG" else "")
        } else {
            "BAT --"
        }
        binding.statusBattery.text = batteryText

        val low = s.batteryPct in 0..15 && !s.charging
        binding.statusBattery.setTextColor(
            ContextCompat.getColor(this, if (low) R.color.nexus_red else R.color.nexus_green)
        )
    }

    // ---------- PageActions ----------

    override fun onTileClick(tile: QuickTile) {
        when (tile.id) {
            "terminal" -> openTerminal()
            "vscode" -> {
                val ok = apps.launchFirstInstalled("com.microsoft.vscode") ||
                        apps.openUrl("https://vscode.dev")
                toastIfFailed(ok, "VS Code")
            }
            "browser" -> toastIfFailed(apps.openUrl("https://www.google.com"), "Browser")
            "files" -> toastIfFailed(
                apps.launchFirstInstalled(
                    "com.google.android.apps.nbu.files",
                    "com.google.android.documentsui",
                    "com.android.documentsui",
                    "com.sec.android.app.myfiles"
                ),
                "Files"
            )
            "spotify" -> toastIfFailed(apps.launchPackage("com.spotify.music"), "Spotify")
            "discord" -> toastIfFailed(apps.launchPackage("com.discord"), "Discord")
            "ytmusic" -> toastIfFailed(
                apps.launchPackage("com.google.android.apps.youtube.music"),
                "YouTube Music"
            )
            "camera" -> toastIfFailed(
                apps.start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
                "Camera"
            )
            "calc" -> toastIfFailed(
                apps.start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)),
                "Calculator"
            )
            "clock" -> toastIfFailed(apps.start(Intent(AlarmClock.ACTION_SHOW_ALARMS)), "Clock")
            "mute" -> {
                val audio = getSystemService(AudioManager::class.java)
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            "settings" -> toastIfFailed(apps.start(Intent(Settings.ACTION_SETTINGS)), "Settings")
        }
    }

    override fun onMediaPrev() = MediaRepository.previous()

    override fun onMediaPlayPause() = MediaRepository.playPause()

    override fun onMediaNext() = MediaRepository.next()

    override fun onMediaCardClick() {
        if (!MediaRepository.isAccessGranted(this)) {
            apps.start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // ---------- util ----------

    private fun toastIfFailed(ok: Boolean, name: String) {
        if (!ok) Toast.makeText(this, "$name tidak tersedia", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_COUNT = 4
        const val WEATHER_REFRESH_MS = 15 * 60 * 1000L
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}