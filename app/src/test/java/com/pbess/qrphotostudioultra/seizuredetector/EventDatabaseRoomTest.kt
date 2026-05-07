package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class EventDatabaseRoomTest {

    private lateinit var database: EventDatabase
    private lateinit var dao: EventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.eventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `room insert read observe update works`() = runTest {
        val eventId = "test-event-1"
        val inserted = dao.insertEvent(
            EventEntity(
                eventId = eventId,
                detectedAt = System.currentTimeMillis(),
                detectedBy = "watch",
                alertState = AlertState.DETECTED,
                countdownDurationSeconds = 20
            )
        )
        assertEquals(1L, inserted)

        val fetched = dao.getEventById(eventId)
        assertNotNull(fetched)
        assertEquals(eventId, fetched?.eventId)
        assertEquals("NOT_REVIEWED", fetched?.reviewStatus)
        assertEquals(null, fetched?.eventCategory)
        assertEquals(null, fetched?.reviewedAt)
        assertEquals(0, fetched?.smsSuccessCount)
        assertEquals(0, fetched?.smsFailureCount)

        val observed = dao.observeRecentEvents(10).first()
        assertEquals(1, observed.size)
        assertEquals(eventId, observed.first().eventId)

        val updatedRows = dao.updateEvent(
            fetched!!.copy(
                alertState = AlertState.CANCELLED_BY_USER,
                cancelledAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        assertEquals(1, updatedRows)

        val updated = dao.getEventById(eventId)
        assertEquals(AlertState.CANCELLED_BY_USER, updated?.alertState)
    }
}
