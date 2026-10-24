package com.example.data

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {

    @Test
    fun testMigration1To2_preservesExistingTasksAndCreatesOfflineQueue() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "test_migration_1_2.db"
        context.deleteDatabase(dbName)

        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create version 1 schema of tasks table
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tasks` (" +
                            "`id` TEXT NOT NULL, " +
                            "`user_id` TEXT NOT NULL, " +
                            "`task_name` TEXT NOT NULL, " +
                            "`description` TEXT NOT NULL, " +
                            "`due_date` TEXT NOT NULL, " +
                            "`due_date_epoch_day` INTEGER NOT NULL, " +
                            "`due_time` TEXT NOT NULL, " +
                            "`assigned_family_member` TEXT NOT NULL, " +
                            "`assigned_member_id` TEXT NOT NULL, " +
                            "`is_completed` INTEGER NOT NULL, " +
                            "`priority` TEXT NOT NULL, " +
                            "`category` TEXT NOT NULL, " +
                            "`points` INTEGER NOT NULL, " +
                            "`recurrence` TEXT NOT NULL, " +
                            "`completed_at_millis` INTEGER, " +
                            "`completed_by_member_id` TEXT, " +
                            "`created_at` INTEGER, " +
                            "`updated_at` INTEGER, " +
                            "PRIMARY KEY(`id`))"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val v1Db = helper.writableDatabase

        // Insert legacy task into version 1 database
        val values = ContentValues().apply {
            put("id", "task_v1_legacy_101")
            put("user_id", "user_alice")
            put("task_name", "Legacy Homework Task")
            put("description", "Math homework from v1")
            put("due_date", "18:00")
            put("due_date_epoch_day", 20000L)
            put("due_time", "18:00")
            put("assigned_family_member", "user_alice")
            put("assigned_member_id", "user_alice")
            put("is_completed", 0)
            put("priority", "HIGH")
            put("category", "HOMEWORK")
            put("points", 25)
            put("recurrence", "DAILY")
        }
        v1Db.insert("tasks", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)

        // Verify task exists in v1
        val cursorV1 = v1Db.query("SELECT id, task_name, points FROM tasks WHERE id = 'task_v1_legacy_101'")
        assertTrue(cursorV1.moveToFirst())
        assertEquals("Legacy Homework Task", cursorV1.getString(1))
        assertEquals(25, cursorV1.getInt(2))
        cursorV1.close()

        // Execute MIGRATION_1_2
        AppDatabase.MIGRATION_1_2.migrate(v1Db)

        // 1. Verify pre-existing data is preserved 100%
        val cursorAfterMigration = v1Db.query("SELECT id, task_name, points, family_id, approved_by_parent FROM tasks WHERE id = 'task_v1_legacy_101'")
        assertTrue("Migrated database must preserve existing records", cursorAfterMigration.moveToFirst())
        assertEquals("task_v1_legacy_101", cursorAfterMigration.getString(0))
        assertEquals("Legacy Homework Task", cursorAfterMigration.getString(1))
        assertEquals(25, cursorAfterMigration.getInt(2))
        assertEquals("", cursorAfterMigration.getString(3)) // default value for new family_id column
        assertEquals(0, cursorAfterMigration.getInt(4)) // default value for approved_by_parent
        cursorAfterMigration.close()

        // 2. Verify offline_sync_queue table exists and can receive records
        val queueValues = ContentValues().apply {
            put("task_id", "task_v1_legacy_101")
            put("family_id", "fam_wexler")
            put("action_type", "UPDATE")
            put("payload_json", "{\"isCompleted\":true}")
            put("timestamp", System.currentTimeMillis())
        }
        val insertQueueId = v1Db.insert("offline_sync_queue", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, queueValues)
        assertTrue("Offline sync queue table must accept insertions post-migration", insertQueueId > 0)

        val queueCursor = v1Db.query("SELECT task_id, action_type FROM offline_sync_queue WHERE id = $insertQueueId")
        assertTrue(queueCursor.moveToFirst())
        assertEquals("task_v1_legacy_101", queueCursor.getString(0))
        assertEquals("UPDATE", queueCursor.getString(1))
        queueCursor.close()

        v1Db.close()

        // 3. Room Schema Validation: Open the migrated database through Room runtime
        // This validates Room's internal schema contract (RoomOpenHelper.onValidateSchema)
        val roomDb = androidx.room.Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbName
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

        kotlinx.coroutines.runBlocking {
            val taskFromRoom = roomDb.taskDao().getTaskById("task_v1_legacy_101")
            assertNotNull("Room must open and validate schema, returning migrated task", taskFromRoom)
            assertEquals("Legacy Homework Task", taskFromRoom?.taskName)
            assertEquals(25, taskFromRoom?.points)
            assertEquals("", taskFromRoom?.familyId)
            assertEquals(false, taskFromRoom?.approvedByParent)
            assertEquals(false, taskFromRoom?.hasDueTime)
            assertEquals("NONE", taskFromRoom?.reminderOffset)
        }
        roomDb.close()

        context.deleteDatabase(dbName)
    }
}
