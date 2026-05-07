package com.pbess.qrphotostudioultra.seizuredetector

import kotlinx.coroutines.flow.Flow

class EventRepository(private val eventDao: EventDao) {

    fun observeRecentEvents(limit: Int = 50): Flow<List<EventEntity>> {
        logD("observeRecentEvents subscribed limit=$limit")
        return eventDao.observeRecentEvents(limit)
    }

    suspend fun getEventById(eventId: String): EventEntity? {
        return eventDao.getEventById(eventId)
    }

    suspend fun createDetectedEvent(
        eventId: String,
        detectedAt: Long,
        detectedBy: String,
        countdownDurationSeconds: Int
    ) {
        logD("createDetectedEvent called eventId=$eventId detectedBy=$detectedBy")
        val event = EventEntity(
            eventId = eventId,
            detectedAt = detectedAt,
            detectedBy = detectedBy,
            alertState = AlertState.DETECTED,
            countdownDurationSeconds = countdownDurationSeconds
        )
        val rowId = eventDao.insertEvent(event)
        if (rowId == -1L) {
            logW("insertEvent ignored by conflict eventId=$eventId")
        } else {
            logD("insertEvent success rowId=$rowId eventId=$eventId")
        }
        val rowCount = eventDao.getRowCount()
        val latest = eventDao.getLatestEvent()
        logD("rowCount=$rowCount latestEventId=${latest?.eventId}")
    }

    suspend fun markCountdownStarted(eventId: String) {
        logD("markCountdownStarted called eventId=$eventId")
        updateState(eventId, AlertState.COUNTDOWN_STARTED) {
            it.copy(countdownStartedAt = System.currentTimeMillis())
        }
    }

    suspend fun markCancelled(eventId: String, cancelSource: String) {
        logD("markCancelled called eventId=$eventId cancelSource=$cancelSource")
        updateState(eventId, AlertState.CANCELLED_BY_USER) {
            val now = System.currentTimeMillis()
            it.copy(
                cancelledAt = now,
                cancelSource = cancelSource,
                failureCategory = "CANCELLED",
                resolvedAt = it.resolvedAt ?: now
            )
        }
    }

    suspend fun markSmsPending(eventId: String, recipientCount: Int) {
        logD("markSmsPending called eventId=$eventId recipients=$recipientCount")
        updateState(eventId, AlertState.SMS_PENDING) {
            it.copy(
                smsAttemptedAt = System.currentTimeMillis(),
                smsRecipientCount = recipientCount,
                deliveryAttempted = true
            )
        }
    }

    suspend fun markSmsSent(
        eventId: String,
        recipientCount: Int,
        successCount: Int,
        failureCount: Int
    ) {
        logD("markSmsSent called eventId=$eventId recipients=$recipientCount success=$successCount failure=$failureCount")
        updateState(eventId, AlertState.SMS_SENT) {
            val now = System.currentTimeMillis()
            it.copy(
                smsCompletedAt = now,
                smsRecipientCount = recipientCount,
                smsSuccessCount = successCount,
                smsFailureCount = failureCount,
                smsSendResult = "SUCCESS",
                deliveryAttempted = true,
                deliveryCompleted = true,
                failureCategory = null,
                resolvedAt = it.resolvedAt ?: now
            )
        }
    }

    suspend fun markSmsFailed(
        eventId: String,
        failureCategory: String,
        recipientCount: Int,
        successCount: Int,
        failureCount: Int
    ) {
        logD("markSmsFailed called eventId=$eventId failureCategory=$failureCategory recipients=$recipientCount success=$successCount failure=$failureCount")
        updateState(eventId, AlertState.SMS_FAILED) {
            val now = System.currentTimeMillis()
            it.copy(
                smsCompletedAt = now,
                smsRecipientCount = recipientCount,
                smsSuccessCount = successCount,
                smsFailureCount = failureCount,
                smsSendResult = failureCategory,
                deliveryAttempted = true,
                deliveryCompleted = true,
                failureCategory = failureCategory,
                resolvedAt = it.resolvedAt ?: now
            )
        }
    }

    suspend fun markReviewed(
        eventId: String,
        reviewStatus: String,
        eventCategory: String?,
        userNotes: String?,
        injuryOccurred: Boolean?,
        medicationTaken: Boolean?,
        emergencyServicesContacted: Boolean?,
        recoveryDurationMinutes: Int?
    ) {
        val event = eventDao.getEventById(eventId)
        if (event == null) {
            logE("markReviewed missing event eventId=$eventId")
            return
        }
        val rows = eventDao.updateEvent(
            event.copy(
                reviewStatus = reviewStatus,
                eventCategory = eventCategory,
                userNotes = userNotes,
                reviewedAt = System.currentTimeMillis(),
                injuryOccurred = injuryOccurred,
                medicationTaken = medicationTaken,
                emergencyServicesContacted = emergencyServicesContacted,
                recoveryDurationMinutes = recoveryDurationMinutes,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (rows == 0) {
            logE("markReviewed updated 0 rows eventId=$eventId")
        } else {
            logD("markReviewed success eventId=$eventId reviewStatus=$reviewStatus")
        }
    }

    suspend fun clearReview(eventId: String) {
        val event = eventDao.getEventById(eventId)
        if (event == null) {
            logE("clearReview missing event eventId=$eventId")
            return
        }
        val rows = eventDao.updateEvent(
            event.copy(
                reviewStatus = "NOT_REVIEWED",
                eventCategory = null,
                userNotes = null,
                reviewedAt = null,
                injuryOccurred = null,
                medicationTaken = null,
                emergencyServicesContacted = null,
                recoveryDurationMinutes = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (rows == 0) {
            logE("clearReview updated 0 rows eventId=$eventId")
        } else {
            logD("clearReview success eventId=$eventId")
        }
    }

    suspend fun updateLocation(eventId: String, latitude: Double, longitude: Double) {
        val event = eventDao.getEventById(eventId)
        if (event != null) {
            val rows = eventDao.updateEvent(event.copy(
                latitude = latitude,
                longitude = longitude,
                locationIncluded = true,
                updatedAt = System.currentTimeMillis()
            ))
            if (rows == 0) {
                logE("updateLocation updated 0 rows eventId=$eventId")
            }
        } else {
            logE("updateLocation missing event eventId=$eventId")
        }
    }

    private suspend fun updateState(
        eventId: String,
        newState: AlertState,
        transform: (EventEntity) -> EventEntity = { it }
    ) {
        val event = eventDao.getEventById(eventId)
        if (event != null) {
            val now = System.currentTimeMillis()
            var updatedEvent = transform(event).copy(
                alertState = newState,
                updatedAt = now
            )
            if (newState.isTerminal() && updatedEvent.resolvedAt == null) {
                updatedEvent = updatedEvent.copy(resolvedAt = now)
            }
            val rows = eventDao.updateEvent(updatedEvent)
            if (rows == 0) {
                logE("updateState updated 0 rows eventId=$eventId state=$newState")
            } else {
                logD("updateState success eventId=$eventId state=$newState")
            }
        } else {
            logE("updateState missing event eventId=$eventId state=$newState")
        }
    }

    companion object {
        private const val TAG = "EventRepository"
    }

    private fun logD(message: String) {
        runCatching { android.util.Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { android.util.Log.w(TAG, message) }
    }

    private fun logE(message: String) {
        runCatching { android.util.Log.e(TAG, message) }
    }

    private fun AlertState.isTerminal(): Boolean =
        this == AlertState.CANCELLED_BY_USER ||
            this == AlertState.SMS_SENT ||
            this == AlertState.SMS_FAILED ||
            this == AlertState.EXPIRED ||
            this == AlertState.UNKNOWN_FAILURE
}
