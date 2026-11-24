package com.nexus.cli

/**
 * Daftar widget default untuk Halaman 2 (Quick Launch).
 * MainActivity memanggil QuickWidgets.default saat SharedPreferences kosong.
 */
object QuickWidgets {
    val default: Set<String> = linkedSetOf(
        "terminal",
        "vscode",
        "browser",
        "files",
        "spotify",
        "discord",
        "ytmusic",
        "camera",
        "calc",
        "clock",
        "mute",
        "settings"
    )
}