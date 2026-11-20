package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoProjectEntity::class, ViralClipEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ClipPulseDatabase : RoomDatabase() {
    abstract fun clipPulseDao(): ClipPulseDao

    companion object {
        @Volatile
        private var INSTANCE: ClipPulseDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE video_projects ADD COLUMN youtubeUrl TEXT")
                db.execSQL("ALTER TABLE video_projects ADD COLUMN youtubeVideoId TEXT")
            }
        }

        fun getInstance(context: Context): ClipPulseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipPulseDatabase::class.java,
                    "clippulse_studio.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
