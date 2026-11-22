package com.nexus.cli

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class ShellContext(
    val appContext: Context,
    val fs: VirtualFS,
    val apps: AppRepository,
    val weather: WeatherRepository,
    val monitor: SystemMonitor
)

class Command(
    val name: String,
    val usage: String,
    val description: String,
    val run: suspend (ShellContext, List<String>) -> ShellResult
)

class CommandRegistry {

    private val map = LinkedHashMap<String, Command>()

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.forLanguageTag("id-ID"))

    init {
        registerAll()
    }

    fun find(name: String): Command? = map[name.lowercase()]

    fun all(): List<Command> = map.values.toList()

    private fun add(cmd: Command) {
        map[cmd.name.lowercase()] = cmd
    }

    private fun registerAll() {
        add(Command("help", "help", "Tampilkan daftar perintah") { _, _ ->
            ShellResult.out(
                listOf("NEXUS-CLI v2.0 — perintah tersedia:") +
                        all().map { "  ${it.usage.padEnd(16)} ${it.description}" }
            )
        })

        add(Command("clear", "clear", "Bersihkan layar terminal") { _, _ ->
            ShellResult(clear = true)
        })

        add(Command("time", "time", "Tampilkan jam sekarang") { _, _ ->
            ShellResult.out(LocalTime.now().format(timeFmt))
        })

        add(Command("date", "date", "Tampilkan tanggal hari ini") { _, _ ->
            ShellResult.out(LocalDate.now().format(dateFmt))
        })

        add(Command("apps", "apps", "Daftar aplikasi terpasang") { ctx, _ ->
            val list = ctx.apps.listApps()
            if (list.isEmpty()) {
                ShellResult.error("apps: tidak ada aplikasi ditemukan")
            } else {
                ShellResult.out(
                    listOf("${list.size} aplikasi terpasang:") +
                            list.mapIndexed { i, a ->
                                "%3d. %-24s %s".format(i + 1, a.label.take(24), a.packageName)
                            }
                )
            }
        })

        add(Command("open", "open <app>", "Buka aplikasi berdasarkan nama") { ctx, args ->
            if (args.isEmpty()) {
                ShellResult.error("usage: open <nama-app>")
            } else {
                val query = args.joinToString(" ")
                val app = ctx.apps.findApp(query)
                if (app == null) {
                    ShellResult.error("open: aplikasi '$query' tidak ditemukan (coba 'apps')")
                } else if (ctx.apps.launchPackage(app.packageName)) {
                    ShellResult.out("Membuka ${app.label}…")
                } else {
                    ShellResult.error("open: gagal membuka ${app.label}")
                }
            }
        })

        add(Command("alias", "alias [n=cmd]", "Lihat/buat alias (alias -r nama = hapus)") { ctx, args ->
            val joined = args.joinToString(" ").trim()
            when {
                joined.isEmpty() -> {
                    val all = ctx.fs.aliases
                    if (all.isEmpty()) {
                        ShellResult.out("(belum ada alias)")
                    } else {
                        ShellResult.out(all.map { "alias ${it.key}='${it.value}'" })
                    }
                }
                args[0] == "-r" -> {
                    val name = args.getOrNull(1)
                    if (name == null) {
                        ShellResult.error("usage: alias -r <nama>")
                    } else if (ctx.fs.removeAlias(name)) {
                        ShellResult.out("alias '$name' dihapus")
                    } else {
                        ShellResult.error("alias: $name: tidak ditemukan")
                    }
                }
                joined.contains('=') -> {
                    val idx = joined.indexOf('=')
                    val name = joined.substring(0, idx).trim()
                    val value = joined.substring(idx + 1).trim().trim('\'', '"')
                    if (name.isEmpty() || name.contains(' ') || name == "alias") {
                        ShellResult.error("alias: nama tidak valid")
                    } else if (value.isEmpty()) {
                        ShellResult.error("alias: perintah kosong")
                    } else {
                        ctx.fs.setAlias(name, value)
                        ShellResult.out("alias $name='$value'")
                    }
                }
                else -> {
                    val v = ctx.fs.aliasOf(joined)
                    if (v == null) {
                        ShellResult.error("alias: $joined: tidak ditemukan")
                    } else {
                        ShellResult.out("alias $joined='$v'")
                    }
                }
            }
        })

        add(Command("cd", "cd [path]", "Pindah direktori (VirtualFS)") { ctx, args ->
            val err = ctx.fs.cd(args.firstOrNull())
            if (err == null) ShellResult() else ShellResult.error(err)
        })

        add(Command("ls", "ls [-l] [path]", "Daftar isi direktori") { ctx, args ->
            val long = args.contains("-l")
            val path = args.firstOrNull { !it.startsWith("-") }
            val nodes = ctx.fs.ls(path)
            if (nodes == null) {
                ShellResult.error("ls: cannot access '$path': No such file or directory")
            } else if (nodes.isEmpty()) {
                ShellResult()
            } else if (long) {
                ShellResult.out(nodes.map {
                    val kind = if (it.isDir) "d" else "-"
                    val size = if (it.isDir) it.children.size else it.content.length
                    "${kind}rw-  ${size.toString().padStart(6)}  ${it.name}${if (it.isDir) "/" else ""}"
                })
            } else {
                ShellResult.out(nodes.map { if (it.isDir) it.name + "/" else it.name })
            }
        })

        add(Command("rm", "rm [-r] <path>", "Hapus file/direktori virtual") { ctx, args ->
            val recursive = args.any { it == "-r" || it == "-rf" || it == "-R" }
            val targets = args.filter { !it.startsWith("-") }
            if (targets.isEmpty()) {
                ShellResult.error("usage: rm [-r] <path>")
            } else {
                val errors = targets.mapNotNull { ctx.fs.rm(it, recursive) }
                if (errors.isEmpty()) {
                    ShellResult()
                } else {
                    ShellResult(errors.map { TerminalLine(it, LineType.ERROR) })
                }
            }
        })

        add(Command("neofetch", "neofetch", "Info sistem") { ctx, _ ->
            val (usedMb, totalMb) = ctx.monitor.memoryUsageMb()
            val (battery, charging) = ctx.monitor.batteryInfo()
            val upMin = SystemClock.elapsedRealtime() / 60000L
            val art = listOf(
                "  +-----------+  ",
                "  |  >_ NEXUS |  ",
                "  |  -------  |  ",
                "  |  CLI 2.0  |  ",
                "  +-----------+  "
            )
            val info = listOf(
                "nexus@launcher",
                "--------------",
                "OS:      Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                "Host:    ${Build.MANUFACTURER} ${Build.MODEL}",
                "Kernel:  ${System.getProperty("os.version") ?: "?"}",
                "Uptime:  ${upMin / 60}h ${upMin % 60}m",
                "Shell:   NexusShell 2.0",
                "CPU:     ${Runtime.getRuntime().availableProcessors()} cores (${Build.HARDWARE})",
                "RAM:     $usedMb / $totalMb MB",
                "Battery: ${if (battery >= 0) "$battery%" else "?"}${if (charging) " (charging)" else ""}"
            )
            val pad = " ".repeat(17)
            ShellResult.out(info.indices.map { (art.getOrNull(it) ?: pad) + info[it] })
        })

        add(Command("weather", "weather", "Cuaca terkini (Open-Meteo)") { ctx, _ ->
            ctx.weather.fetch().fold(
                onSuccess = { w ->
                    ShellResult.out(
                        listOf("${w.city}: ${w.condition}, ${w.tempC}°C, angin ${w.windKmh} km/h") +
                                w.daily.map {
                                    "  ${it.label.padEnd(10)} ${it.condition.padEnd(18)} ${it.minC}°/${it.maxC}°"
                                }
                    )
                },
                onFailure = { e ->
                    ShellResult.error("weather: gagal mengambil data (${e.message ?: "offline?"})")
                }
            )
        })

        add(Command("exit-Nexus", "exit-Nexus", "Lepas kiosk mode & keluar aplikasi") { _, _ ->
            ShellResult(
                lines = listOf(TerminalLine("Melepas kiosk mode… sampai jumpa.", LineType.SYSTEM)),
                exit = true
            )
        })
    }
}