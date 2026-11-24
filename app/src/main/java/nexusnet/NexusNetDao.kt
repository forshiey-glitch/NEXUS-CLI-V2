package com.nexus.cli.nexusnet

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NexusNetDao {

    @Insert
    suspend fun insert(items: List<HasilScan>)

    @Query("SELECT * FROM hasil_scan ORDER BY waktu DESC, id DESC")
    suspend fun getAll(): List<HasilScan>

    @Query("SELECT * FROM hasil_scan ORDER BY waktu DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<HasilScan>

    @Query("DELETE FROM hasil_scan")
    suspend fun clear()

    @Query("DELETE FROM hasil_scan WHERE id NOT IN (SELECT id FROM hasil_scan ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}