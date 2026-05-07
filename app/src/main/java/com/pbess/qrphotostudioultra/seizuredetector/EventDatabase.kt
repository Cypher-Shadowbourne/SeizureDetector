package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [EventEntity::class], version = 4, exportSchema = false)
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE events ADD COLUMN reviewStatus TEXT DEFAULT 'NOT_REVIEWED'")
                database.execSQL("ALTER TABLE events ADD COLUMN eventCategory TEXT")
                database.execSQL("ALTER TABLE events ADD COLUMN userNotes TEXT")
                database.execSQL("ALTER TABLE events ADD COLUMN reviewedAt INTEGER")
                database.execSQL("ALTER TABLE events ADD COLUMN injuryOccurred INTEGER")
                database.execSQL("ALTER TABLE events ADD COLUMN medicationTaken INTEGER")
                database.execSQL("ALTER TABLE events ADD COLUMN emergencyServicesContacted INTEGER")
                database.execSQL("ALTER TABLE events ADD COLUMN recoveryDurationMinutes INTEGER")
                database.execSQL("UPDATE events SET reviewStatus = 'NOT_REVIEWED' WHERE reviewStatus IS NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE events ADD COLUMN smsSuccessCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE events ADD COLUMN smsFailureCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private const val TAG = "EventDatabase"
    }
}
