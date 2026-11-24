package com.nexus.cli.nexusnet

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HasilScan::class],
    version = 1,
    exportSchema = false
)
abstract class NexusNetDatabase : RoomDatabase() {

    abstract fun dao(): NexusNetDao

    companion object {
        @Volatile
        private var INSTANCE: NexusNetDatabase? = null

        fun getInstance(context: Context): NexusNetDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexusNetDatabase::class.java,
                    "nexus_net.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}