package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EventDatabaseMigrationTest {

    private val dbName = "migration_test.db"
    private lateinit var context: Context
    private var database: EventDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        createVersion1Database()
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 1 to 2 preserves existing rows and backfills defaults`() = runTest {
        database = Room.databaseBuilder(context, EventDatabase::class.java, dbName)
            .addMigrations(EventDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val dao = database!!.eventDao()
        val terminal = dao.getEventById("old-terminal")
        assertNotNull(terminal)
        assertEquals("watch", terminal!!.detectedBy)
        assertEquals(terminal.updatedAt, terminal.resolvedAt)
        assertEquals(false, terminal.deliveryAttempted)
        assertEquals(false, terminal.deliveryCompleted)
        assertEquals(null, terminal.cancelSource)
        assertEquals(null, terminal.failureCategory)

        val open = dao.getEventById("old-open")
        assertNotNull(open)
        assertEquals(null, open!!.resolvedAt)
        assertEquals(false, open.deliveryAttempted)
        assertEquals(false, open.deliveryCompleted)
    }

    private fun createVersion1Database() {
        val sqlite = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events (
                eventId TEXT NOT NULL PRIMARY KEY,
                detectedAt INTEGER NOT NULL,
                detectedBy TEXT NOT NULL,
                alertState TEXT NOT NULL,
                countdownStartedAt INTEGER,
                countdownDurationSeconds INTEGER NOT NULL,
                cancelledAt INTEGER,
                smsAttemptedAt INTEGER,
                smsCompletedAt INTEGER,
                smsSendResult TEXT,
                smsRecipientCount INTEGER NOT NULL,
                locationIncluded INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                peakTriggerScore REAL,
                accelMagnitude REAL,
                gyroMagnitude REAL,
                heartRate REAL,
                pressure REAL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO events (
                eventId, detectedAt, detectedBy, alertState,
                countdownDurationSeconds, smsRecipientCount, locationIncluded, createdAt, updatedAt
            ) VALUES (
                'old-terminal', 1000, 'watch', 'SMS_SENT',
                20, 1, 0, 2000, 3000
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO events (
                eventId, detectedAt, detectedBy, alertState,
                countdownDurationSeconds, smsRecipientCount, locationIncluded, createdAt, updatedAt
            ) VALUES (
                'old-open', 1001, 'watch', 'DETECTED',
                20, 0, 0, 2001, 3001
            )
            """.trimIndent()
        )
        sqlite.version = 1
        sqlite.close()
    }
}
