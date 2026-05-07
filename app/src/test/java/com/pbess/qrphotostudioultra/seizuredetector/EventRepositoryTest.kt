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

        repository.markSmsSent(
            eventId = eventId,
            recipientCount = 2,
            successCount = 2,
            failureCount = 0
        )

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        
        val captured = captor.firstValue
        assertEquals(AlertState.SMS_SENT, captured.alertState)
        assertEquals(2, captured.smsRecipientCount)
        assertEquals(2, captured.smsSuccessCount)
        assertEquals(0, captured.smsFailureCount)
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

        repository.markSmsPending(eventId, 3)

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        assertEquals(true, captor.firstValue.deliveryAttempted)
        assertEquals(3, captor.firstValue.smsRecipientCount)
    }

    @Test
    fun `markSmsFailed sets failure category and delivery completed`() = runTest {
        val eventId = "test-id"
        val existingEvent = EventEntity(eventId, 1000L, "watch", AlertState.SMS_PENDING, countdownDurationSeconds = 30)
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markSmsFailed(
            eventId = eventId,
            failureCategory = "SMS_PROVIDER",
            recipientCount = 3,
            successCount = 1,
            failureCount = 2
        )

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        val captured = captor.firstValue
        assertEquals(AlertState.SMS_FAILED, captured.alertState)
        assertEquals(true, captured.deliveryAttempted)
        assertEquals(true, captured.deliveryCompleted)
        assertEquals("SMS_PROVIDER", captured.failureCategory)
        assertEquals(3, captured.smsRecipientCount)
        assertEquals(1, captured.smsSuccessCount)
        assertEquals(2, captured.smsFailureCount)
    }

    @Test
    fun `duplicate eventId is ignored by insert conflict`() = runTest {
        whenever(eventDao.insertEvent(any())).thenReturn(-1L)
        repository.createDetectedEvent("dup-id", 1000L, "watch", 20)
        verify(eventDao).insertEvent(any())
    }

    @Test
    fun `markReviewed stores review fields and keeps alert and sms fields unchanged`() = runTest {
        val eventId = "review-id"
        val existingEvent = EventEntity(
            eventId = eventId,
            detectedAt = 1000L,
            detectedBy = "watch",
            alertState = AlertState.SMS_SENT,
            countdownDurationSeconds = 30,
            smsRecipientCount = 2,
            smsSuccessCount = 2,
            smsFailureCount = 0,
            smsSendResult = "SUCCESS",
            deliveryAttempted = true,
            deliveryCompleted = true
        )
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.markReviewed(
            eventId = eventId,
            reviewStatus = "REAL_EVENT",
            eventCategory = "POSSIBLE_SEIZURE",
            userNotes = "Recovered after rest",
            injuryOccurred = true,
            medicationTaken = false,
            emergencyServicesContacted = false,
            recoveryDurationMinutes = 15
        )

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        val captured = captor.firstValue
        assertEquals("REAL_EVENT", captured.reviewStatus)
        assertEquals("POSSIBLE_SEIZURE", captured.eventCategory)
        assertEquals("Recovered after rest", captured.userNotes)
        assertEquals(true, captured.injuryOccurred)
        assertEquals(false, captured.medicationTaken)
        assertEquals(false, captured.emergencyServicesContacted)
        assertEquals(15, captured.recoveryDurationMinutes)
        assertNotNull(captured.reviewedAt)
        assertEquals(AlertState.SMS_SENT, captured.alertState)
        assertEquals(true, captured.deliveryAttempted)
        assertEquals(true, captured.deliveryCompleted)
        assertEquals("SUCCESS", captured.smsSendResult)
        assertEquals(2, captured.smsRecipientCount)
        assertEquals(2, captured.smsSuccessCount)
        assertEquals(0, captured.smsFailureCount)
    }

    @Test
    fun `clearReview resets review fields only`() = runTest {
        val eventId = "review-clear-id"
        val existingEvent = EventEntity(
            eventId = eventId,
            detectedAt = 1000L,
            detectedBy = "watch",
            alertState = AlertState.SMS_FAILED,
            countdownDurationSeconds = 30,
            smsRecipientCount = 1,
            smsSuccessCount = 0,
            smsFailureCount = 1,
            smsSendResult = "SMS_PROVIDER",
            deliveryAttempted = true,
            deliveryCompleted = true,
            reviewStatus = "UNSURE",
            eventCategory = "UNKNOWN",
            userNotes = "Not clear",
            reviewedAt = 2000L,
            injuryOccurred = true,
            medicationTaken = true,
            emergencyServicesContacted = false,
            recoveryDurationMinutes = 22
        )
        whenever(eventDao.getEventById(eventId)).thenReturn(existingEvent)

        repository.clearReview(eventId)

        val captor = argumentCaptor<EventEntity>()
        verify(eventDao).updateEvent(captor.capture())
        val captured = captor.firstValue
        assertEquals("NOT_REVIEWED", captured.reviewStatus)
        assertEquals(null, captured.eventCategory)
        assertEquals(null, captured.userNotes)
        assertEquals(null, captured.reviewedAt)
        assertEquals(null, captured.injuryOccurred)
        assertEquals(null, captured.medicationTaken)
        assertEquals(null, captured.emergencyServicesContacted)
        assertEquals(null, captured.recoveryDurationMinutes)
        assertEquals(AlertState.SMS_FAILED, captured.alertState)
        assertEquals(true, captured.deliveryAttempted)
        assertEquals(true, captured.deliveryCompleted)
        assertEquals("SMS_PROVIDER", captured.smsSendResult)
        assertEquals(1, captured.smsRecipientCount)
        assertEquals(0, captured.smsSuccessCount)
        assertEquals(1, captured.smsFailureCount)
    }
}
