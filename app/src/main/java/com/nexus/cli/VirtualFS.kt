package com.nexus.cli

import android.content.Context
import com.google.gson.Gson
import java.io.File

const val FS_HOME = "/home/nexus"

data class FsNode(
    val name: String,
    val isDir: Boolean,
    var content: String = "",
    val children: MutableList<FsNode> = mutableListOf()
)

data class FsState(
    var root: FsNode,
    var cwd: String = FS_HOME,
    val aliases: MutableMap<String, String> = mutableMapOf()
)

class VirtualFS(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "virtualfs.json")
    private var state: FsState = load()

    val cwd: String get() = state.cwd
    val aliases: Map<String, String> get() = state.aliases

    // ---------- persistence ----------

    private fun load(): FsState {
        val loaded = try {
            if (file.exists()) gson.fromJson(file.readText(), FsState::class.java) else null
        } catch (e: Exception) {
            null
        }
        return loaded ?: seed().also { persist(it) }
    }

    private fun persist(s: FsState) {
        try {
            file.writeText(gson.toJson(s))
        } catch (e: Exception) {
            // abaikan: FS virtual tetap jalan di memori
        }
    }

    fun save() = persist(state)

    private fun mkDir(name: String, vararg kids: FsNode) =
        FsNode(name, true, children = kids.toMutableList())

    private fun mkFile(name: String, content: String) = FsNode(name, false, content)

    private fun seed(): FsState {
        val root = mkDir(
            "",
            mkDir(
                "home",
                mkDir(
                    "nexus",
                    mkFile("readme.txt", "Selamat datang di NEXUS-CLI v2.0.\nKetik 'help' untuk daftar perintah."),
                    mkFile("notes.txt", "- ship v2.0\n- tes kiosk mode\n- cek izin notifikasi"),
                    mkDir(
                        "projects",
                        mkDir(
                            "nexus-cli",
                            mkFile("README.md", "# NEXUS-CLI\nTerminal-first Android launcher."),
                            mkFile("todo.txt", "[ ] GitHub API token\n[ ] widget baru")
                        )
                    )
                )
            ),
            mkDir("etc", mkFile("motd", "NEXUS-CLI shell")),
            mkDir("tmp")
        )
        return FsState(root = root, cwd = FS_HOME)
    }

    // ---------- path helpers ----------

    private fun split(path: String): List<String> = path.split("/").filter { it.isNotEmpty() }

    private fun resolve(path: String): List<String> {
        var p = path.trim()
        val out = mutableListOf<String>()
        if (p == "~" || p.startsWith("~/")) {
            out.addAll(split(FS_HOME))
            p = p.removePrefix("~")
        } else if (!p.startsWith("/")) {
            out.addAll(split(state.cwd))
        }
        for (seg in p.split("/").filter { it.isNotEmpty() }) {
            when (seg) {
                "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(seg)
            }
        }
        return out
    }

    private fun nodeAt(segments: List<String>): FsNode? {
        var cur = state.root
        for (s in segments) {
            val parent = cur
            cur = parent.children.firstOrNull { it.name == s } ?: return null
        }
        return cur
    }

    // ---------- operations ----------

    fun prompt(): String {
        val c = state.cwd
        val shown = when {
            c == FS_HOME -> "~"
            c.startsWith("$FS_HOME/") -> "~" + c.removePrefix(FS_HOME)
            else -> c
        }
        return "nexus@launcher:$shown$ "
    }

    /** Return null jika sukses, atau pesan error. */
    fun cd(path: String?): String? {
        val target = if (path.isNullOrBlank()) split(FS_HOME) else resolve(path)
        val node = nodeAt(target) ?: return "cd: $path: No such file or directory"
        if (!node.isDir) return "cd: $path: Not a directory"
        state.cwd = "/" + target.joinToString("/")
        save()
        return null
    }

    /** Return null jika path tidak ada. */
    fun ls(path: String?): List<FsNode>? {
        val segs = if (path.isNullOrBlank()) split(state.cwd) else resolve(path)
        val node = nodeAt(segs) ?: return null
        if (!node.isDir) return listOf(node)
        return node.children.sortedWith(compareByDescending<FsNode> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Return null jika sukses, atau pesan error. */
    fun rm(path: String, recursive: Boolean): String? {
        val segs = resolve(path)
        if (segs.isEmpty()) return "rm: refusing to remove '/'"
        val node = nodeAt(segs) ?: return "rm: cannot remove '$path': No such file or directory"
        if (node.isDir && node.children.isNotEmpty() && !recursive) {
            return "rm: cannot remove '$path': Directory not empty (gunakan -r)"
        }
        val parent = nodeAt(segs.dropLast(1)) ?: return "rm: parent directory not found"
        parent.children.removeAll { it === node }
        if (nodeAt(split(state.cwd)) == null) state.cwd = "/"
        save()
        return null
    }

    // ---------- aliases ----------

    fun aliasOf(name: String): String? = state.aliases[name]

    fun setAlias(name: String, value: String) {
        state.aliases[name] = value
        save()
    }

    fun removeAlias(name: String): Boolean {
        val removed = state.aliases.remove(name) != null
        if (removed) save()
        return removed
    }
}