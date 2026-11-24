package com.nexus.cli.nexusnet

/**
 * Abstraksi atas NexusNetDao. TIDAK menyimpan password.
 */
class NexusNetRepository(private val dao: NexusNetDao) {

    suspend fun simpanHasil(targets: List<WifiTarget>) {
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = targets.map {
            HasilScan(
                ssid = it.ssid,
                bssid = it.bssid,
                waktu = now,
                sinyal = it.signalDbm,
                enkripsi = it.encryption
            )
        }
        dao.insert(rows)
        dao.trim(MAX_ROWS)
    }

    suspend fun ambilSemua(): List<HasilScan> = dao.getAll()

    suspend fun ambilTerbaru(limit: Int = 20): List<HasilScan> = dao.getRecent(limit)

    suspend fun bersihkan() = dao.clear()

    private companion object {
        const val MAX_ROWS = 500
    }
}