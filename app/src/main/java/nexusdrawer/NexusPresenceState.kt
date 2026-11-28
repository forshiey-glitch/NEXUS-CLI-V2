package com.nexus.cli.nexusdrawer

enum class NexusPresenceState(val defaultStatus: String) {
    IDLE("Siap membantu"),
    LISTENING("Mendengarkan…"),
    THINKING("Sedang berpikir…"),
    SPEAKING("Menjawab…"),
    ERROR("Terjadi kesalahan")
}