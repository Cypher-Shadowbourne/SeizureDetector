package com.pbess.qrphotostudioultra.seizuredetector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EventRepositoryTest {

    private val eventDao: EventDao = mock()
    private lateinit var repository: EventRepository

    @Before
    fun setup() {
        repository = EventRepository(eventDao)
        runTest {
            whenever(eventDao.insertEvent(any())).thenReturn(1L)
            whenever(eventDao.updateEvent(any())).thenReturn(1)
            whenever(eventDao.getRowCount()).thenReturn(0)
            whenever(eventDao.getLatestEvent()).thenReturn(null)
        }
    }

    @Test
    fun `createDetectedEvent inserts new event`() = runTest {
        val eventId = "test-id"
        repository.createDetectedEvent(eventId, 1000L, "watch", 30)

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).insertEvent(captor.capture())
        
        val captured = captor.firstValue
        assertEquals(eventId, captured.eventId)
        assertEquals(AlertState.DETECTED, captured.alertState)
        assertEquals("watch", captured.detectedBy)
    }

    @Test
    fun `markCountdownStarted updates state`() = runTest {
        val eventId = "test-id"
        val existingEvent = EventEntity(eventId, 1000L, "watch", AlertState.DETECTED, countdownDurationSeconds = 30)
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markCountdownStarted(eventId)

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        
        val captured = captor.firstValue
        assertEquals(AlertState.COUNTDOWN_STARTED, captured.alertState)
        assertNotNull(captured.countdownStartedAt)
    }

    @Test
    fun `markCancelled updates state and source`() = runTest {
        val eventId = "test-id"
        val existingEvent = EventEntity(eventId, 1000L, "watch", AlertState.COUNTDOWN_STARTED, countdownDurationSeconds = 30)
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markCancelled(eventId, "user_phone")

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        
        val captured = captor.firstValue
        assertEquals(AlertState.CANCELLED_BY_USER, captured.alertState)
        assertNotNull(captured.cancelledAt)
        assertNotNull(captured.resolvedAt)
        assertEquals("user_phone", captured.cancelSource)
    }

    @Test
    fun `markSmsSent stores recipient count`() = runTest {
        val eventId = "test-id"
        val existingEvent = EventEntity(eventId, 1000L, "watch", AlertState.SMS_PENDING, countdownDurationSeconds = 30)
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markSmsSent(eventId, 2)

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        
        val captured = captor.firstValue
        assertEquals(AlertState.SMS_SENT, captured.alertState)
        assertEquals(2, captured.smsRecipientCount)
        assertEquals("SUCCESS", captured.smsSendResult)
        assertEquals(true, captured.deliveryAttempted)
        assertEquals(true, captured.deliveryCompleted)
        assertEquals(null, captured.failureCategory)
        assertNotNull(captured.resolvedAt)
    }

    @Test
    fun `markSmsPending sets delivery attempted`() = runTest {
        val eventId = "test-id"
        val existingEvent = EventEntity(eventId, 1000L, "watch", AlertState.COUNTDOWN_STARTED, countdownDurationSeconds = 30)
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markSmsPending(eventId)

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        assertEquals(true, captor.firstValue.deliveryAttempted)
    }

    @Test
    fun `markSmsFailed sets failure category and delivery completed`() = runTest {
        val eventId = "test-id"
        val existingEvent = EventEntity(eventId, 1000L, "watch", AlertState.SMS_PENDING, countdownDurationSeconds = 30)
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markSmsFailed(eventId, "SMS_PROVIDER")

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        val captured = captor.firstValue
        assertEquals(AlertState.SMS_FAILED, captured.alertState)
        assertEquals(true, captured.deliveryAttempted)
        assertEquals(true, captured.deliveryCompleted)
        assertEquals("SMS_PROVIDER", captured.failureCategory)
    }

    @Test
    fun `duplicate eventId is ignored by insert conflict`() = runTest {
        whenever(eventDao.insertEvent(any())).thenReturn(-1L)
        repository.createDetectedEvent("dup-id", 1000L, "watch", 20)
        verify(eventDao).insertEvent(any())
    }
}
