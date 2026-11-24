package com.nightagent.evidence.metadata

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Version 1 local metadata database. Future schema changes must add explicit
 * Room migrations; destructive fallback is intentionally not enabled.
 */
@Database(
    entities = [EvidenceMetadataEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(EvidenceMetadataConverters::class)
abstract class EvidenceMetadataDatabase : RoomDatabase() {

    abstract fun metadataDao(): EvidenceMetadataDao

    companion object {
        const val VERSION: Int = 1
        const val DATABASE_NAME: String = "evidence_metadata.db"

        @Volatile
        private var instance: EvidenceMetadataDatabase? = null

        fun create(context: Context): EvidenceMetadataDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EvidenceMetadataDatabase::class.java,
                DATABASE_NAME
            ).build()

        /** One process-wide database; capture never opens a per-file database. */
        fun getInstance(context: Context): EvidenceMetadataDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }
    }
}
