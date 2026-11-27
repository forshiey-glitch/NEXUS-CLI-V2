package com.nexus.cli

import android.content.Context
import com.nexus.cli.nexusnet.NexusNetCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Jembatan antar komponen. MainActivity mengisi terminalPrinter, terminalClearer, dan micToggle.
 */
object NexusBus {
    @Volatile
    var terminalPrinter: ((String) -> Unit)? = null

    @Volatile
    var terminalClearer: (() -> Unit)? = null

    @Volatile
    var micToggle: ((Boolean) -> Unit)? = null

    fun print(text: String) {
        terminalPrinter?.invoke(text)
    }

    fun clear() {
        terminalClearer?.invoke()
    }
}

class ShellEngine(
    context: Context,
    private val registry: CommandRegistry,
    private val shellContext: ShellContext,
    cpuProvider: () -> Float
) {

    private val appContext = context.applicationContext
    private val sensorCommand = SensorCommand(appContext)
    private val chartCommand = ChartCommand(cpuProvider)

    /** Modul Wi-Fi lab (legal, tanpa cracking). MainActivity mengisi onNeedLocationPermission. */
    val nexusNet = NexusNetCommand(appContext)

    /** True saat terminal menunggu password Wi-Fi: echo harus disamarkan, baris tidak boleh di-parse. */
    val isAwaitingSecret: Boolean get() = nexusNet.isAwaitingPassword

    /** Harus dipanggil dari main thread (sensor, chart, dan media memakai main thread). */
    suspend fun execute(rawLine: String): ShellResult {
        // Baris password langsung ke NexusNetCommand: tanpa trim/tokenize/alias/pipe/redirect.
        if (nexusNet.isAwaitingPassword) return nexusNetSecretInput(rawLine)

        val line = rawLine.trim()
        if (line.isEmpty()) return ShellResult()

        // Redirect: ">" (timpa) atau ">>" (tambah)
        var commandPart = line
        var redirectTarget: String? = null
        var append = false
        val redirectIdx = line.lastIndexOf('>')
        if (redirectIdx >= 0) {
            append = redirectIdx > 0 && line[redirectIdx - 1] == '>'
            redirectTarget = line.substring(redirectIdx + 1).trim()
            commandPart = line.substring(0, if (append) redirectIdx - 1 else redirectIdx).trim()
            if (redirectTarget.isEmpty()) {
                return ShellResult.error("shell: syntax error, target redirect kosong")
            }
        }

        // Pipe: "a | b | c"
        val segments = commandPart.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ShellResult.error("shell: syntax error")

        val allowAsync = segments.size == 1 && redirectTarget == null
        var stdin: String? = null
        var last = ShellResult()

        for (segment in segments) {
            last = runSegment(segment, stdin, allowAsync)
            val hasError = last.lines.any { it.type == LineType.ERROR }
            if (hasError || last.exit) return last
            stdin = last.lines.joinToString("\n") { it.text }
        }

        if (redirectTarget != null) {
            return try {
                shellContext.fs.write(redirectTarget, stdin.orEmpty(), append)
                ShellResult()
            } catch (e: Exception) {
                ShellResult.error("shell: gagal menulis ke $redirectTarget: ${e.message}")
            }
        }
        return last
    }

    private suspend fun runSegment(segment: String, stdin: String?, allowAsync: Boolean): ShellResult {
        val tokens = expandAlias(segment).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ShellResult()
        val name = tokens.first()
        val cmd = name.lowercase(Locale.ROOT)
        val args = tokens.drop(1)

        // Filter pipe
        if (stdin != null) {
            when (cmd) {
                "grep" -> {
                    val needle = args.firstOrNull().orEmpty()
                    return ShellResult.out(stdin.lines().filter { it.contains(needle, ignoreCase = true) })
                }
                "head" -> {
                    val n = args.firstOrNull()?.toIntOrNull() ?: 10
                    return ShellResult.out(stdin.lines().take(n))
                }
                "tail" -> {
                    val n = args.firstOrNull()?.toIntOrNull() ?: 10
                    return ShellResult.out(stdin.lines().takeLast(n))
                }
                "wc" -> {
                    val lines = stdin.lines()
                    val words = stdin.split(Regex("\\s+")).count { it.isNotEmpty() }
                    return ShellResult.out("${lines.size} lines, $words words")
                }
            }
        }

        return when (cmd) {
            "sensor" -> {
                if (!allowAsync) return asyncNotPipeable("sensor")
                sensorCommand.execute(args) { NexusBus.print(it) }
                ShellResult()
            }
            "chart" -> {
                if (!allowAsync) return asyncNotPipeable("chart")
                chartCommand.execute(args) { NexusBus.print(it) }
                ShellResult()
            }
            "voice-on" -> toggleVoice(true)
            "voice-off" -> toggleVoice(false)
            "media" -> mediaCommand(args)
            "nexus-net" -> nexusNetCommand(args)
            else -> runRegistry(cmd, args)
        }
    }

    /** `nexus-net <scan|stop|select|connect|db|log|help>` -> NexusNetCommand. */
    private suspend fun nexusNetCommand(args: List<String>): ShellResult {
        val text = withContext(Dispatchers.Default) { nexusNet.execute(args) }
        return ShellResult.out(text.lines())
    }

    private suspend fun nexusNetSecretInput(raw: String): ShellResult {
        val text = withContext(Dispatchers.Default) {
            nexusNet.handleInput(raw.trimEnd('\r', '\n'))
        }
        return ShellResult.out(text.lines())
    }

    private suspend fun runRegistry(cmd: String, args: List<String>): ShellResult {
        val command = registry.find(cmd)
            ?: return ShellResult.error("$cmd: command not found (ketik 'help')")
        val result = withContext(Dispatchers.Default) { command.run(shellContext, args) }
        if (cmd != "help") return result
        return result.copy(lines = result.lines + EXTRA_HELP.map { TerminalLine(it, LineType.OUTPUT) })
    }

    private fun expandAlias(segment: String): String {
        val first = segment.substringBefore(' ')
        val value = shellContext.fs.aliasOf(first) ?: return segment
        return value + segment.removePrefix(first)
    }

    private fun asyncNotPipeable(name: String): ShellResult =
        ShellResult.error("$name: perintah ini tidak bisa dipakai dengan pipe/redirect.")

    private fun toggleVoice(enable: Boolean): ShellResult {
        val toggle = NexusBus.micToggle
            ?: return ShellResult.error("[voice] UI belum siap, coba lagi.")
        toggle(enable)
        return ShellResult()
    }

    private fun mediaCommand(args: List<String>): ShellResult {
        MediaRepository.attach(appContext)
        val sub = args.firstOrNull()?.lowercase(Locale.ROOT)
        when (sub) {
            "skip", "next" -> MediaRepository.next()
            "prev", "previous", "back" -> MediaRepository.previous()
            "play" -> MediaRepository.play()
            "pause" -> MediaRepository.pause()
            "toggle", "playpause" -> MediaRepository.playPause()
            else -> return ShellResult.error("usage: media <skip|prev|play|pause|toggle>")
        }
        return ShellResult.out("[media] $sub")
    }

    fun close() {
        sensorCommand.stop()
        chartCommand.close()
    }

    private companion object {
        val EXTRA_HELP = listOf(
            "  sensor <list|light|accel|stop>  Baca sensor perangkat",
            "  chart cpu        Grafik batang CPU (10 data terakhir)",
            "  voice-on         Aktifkan mikrofon (Hey Nexus)",
            "  voice-off        Matikan mikrofon",
            "  media <skip|prev|play|pause|toggle>  Kontrol media",
            "  nexus-net <scan|stop|select|connect|db|log|help>  Wi-Fi Lab",
            "  Pipe: cmd | grep|head|tail|wc   Redirect: cmd > file, cmd >> file"
        )
    }
}