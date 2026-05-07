package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [EventEntity::class], version = 2, exportSchema = false)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null

        fun getDatabase(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "event_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                Log.d(TAG, "Created EventDatabase name=event_database")
                instance
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE events ADD COLUMN resolvedAt INTEGER")
                database.execSQL("ALTER TABLE events ADD COLUMN cancelSource TEXT")
                database.execSQL("ALTER TABLE events ADD COLUMN deliveryAttempted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE events ADD COLUMN deliveryCompleted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE events ADD COLUMN failureCategory TEXT")
                database.execSQL(
                    """
                    UPDATE events
                    SET resolvedAt = updatedAt
                    WHERE resolvedAt IS NULL
                    AND alertState IN ('CANCELLED_BY_USER', 'SMS_SENT', 'SMS_FAILED', 'EXPIRED', 'UNKNOWN_FAILURE')
                    """.trimIndent()
                )
            }
        }

        private const val TAG = "EventDatabase"
    }
}
