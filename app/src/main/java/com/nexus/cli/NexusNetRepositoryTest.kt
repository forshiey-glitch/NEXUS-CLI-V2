package com.nexus.cli

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexus.cli.nexusnet.HasilScan
import com.nexus.cli.nexusnet.NexusNetDatabase
import com.nexus.cli.nexusnet.NexusNetRepository
import com.nexus.cli.nexusnet.WifiTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NexusNetRepositoryTest {

    private lateinit var db: NexusNetDatabase
    private lateinit var repo: NexusNetRepository

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, NexusNetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NexusNetRepository(db.dao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun simpanDanAmbil() = runBlocking {
        repo.simpanHasil(
            listOf(
                WifiTarget("Lab", "AA:BB", 6, -45, "WPA2", false)
            )
        )
        val rows: List<HasilScan> = repo.ambilSemua()
        assertEquals(1, rows.size)
        assertEquals("Lab", rows[0].ssid)
        assertEquals("WPA2", rows[0].enkripsi)
    }

    @Test
    fun bersihkan() = runBlocking {
        repo.simpanHasil(listOf(WifiTarget("X", "AA", 1, -70, "OPEN", false)))
        repo.bersihkan()
        assertEquals(0, repo.ambilSemua().size)
    }
}