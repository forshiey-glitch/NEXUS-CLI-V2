package com.nexus.cli.nexusnet

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hasil_scan")
data class HasilScan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ssid: String,
    val bssid: String,
    val waktu: Long,
    val sinyal: Int,
    val enkripsi: String
)
