package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.Task

@Database(
    entities = [Task::class, OfflineSyncQueueEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(RoomDateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun offlineSyncDao(): OfflineSyncDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Safe, non-destructive migration contract preserving user data across versions 1 -> 2.
         * Creates offline_sync_queue table and non-destructively migrates task columns.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure offline sync queue table exists
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_sync_queue` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`task_id` TEXT NOT NULL, " +
                        "`family_id` TEXT NOT NULL, " +
                        "`action_type` TEXT NOT NULL, " +
                        "`payload_json` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
                // Add any missing columns to tasks table non-destructively, preserving existing user data
                runCatching {
                    db.execSQL("ALTER TABLE `tasks` ADD COLUMN `family_id` TEXT NOT NULL DEFAULT ''")
                }
                runCatching {
                    db.execSQL("ALTER TABLE `tasks` ADD COLUMN `approved_by_parent` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_tasks_local.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
