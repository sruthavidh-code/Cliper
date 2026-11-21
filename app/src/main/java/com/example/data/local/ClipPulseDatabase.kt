package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoProjectEntity::class, ViralClipEntity::class, ExportedClipEntity::class],
    version = 4,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE viral_clips ADD COLUMN videoFilter TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exported_clips` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `projectId` INTEGER,
                        `clipId` INTEGER,
                        `title` TEXT NOT NULL,
                        `exportedUri` TEXT NOT NULL,
                        `thumbnailPath` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `resolution` TEXT NOT NULL,
                        `aspectRatio` TEXT NOT NULL,
                        `captionStyle` TEXT NOT NULL,
                        `videoFilter` TEXT NOT NULL,
                        `renderDurationMs` INTEGER NOT NULL,
                        `isDemo` INTEGER NOT NULL,
                        `exportedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exported_clips_exportedAtMs` ON `exported_clips` (`exportedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exported_clips_projectId` ON `exported_clips` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exported_clips_clipId` ON `exported_clips` (`clipId`)")
            }
        }

        fun getInstance(context: Context): ClipPulseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipPulseDatabase::class.java,
                    "clippulse_studio.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
