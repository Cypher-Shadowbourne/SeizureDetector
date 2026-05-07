package com.pbess.qrphotostudioultra.seizuredetector

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AlertState {
    DETECTED,
    COUNTDOWN_STARTED,
    CANCELLED_BY_USER,
    SMS_PENDING,
    SMS_SENT,
    SMS_FAILED,
    EXPIRED,
    UNKNOWN_FAILURE
}

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val detectedAt: Long,
    val detectedBy: String,
    val alertState: AlertState,
    val countdownStartedAt: Long? = null,
    val countdownDurationSeconds: Int,
    val cancelledAt: Long? = null,
    val smsAttemptedAt: Long? = null,
    val smsCompletedAt: Long? = null,
    val smsSendResult: String? = null,
    val smsRecipientCount: Int = 0,
    val smsSuccessCount: Int = 0,
    val smsFailureCount: Int = 0,
    val resolvedAt: Long? = null,
    val cancelSource: String? = null,
    val deliveryAttempted: Boolean = false,
    val deliveryCompleted: Boolean = false,
    val failureCategory: String? = null,
    val reviewStatus: String? = "NOT_REVIEWED",
    val eventCategory: String? = null,
    val userNotes: String? = null,
    val reviewedAt: Long? = null,
    val injuryOccurred: Boolean? = null,
    val medicationTaken: Boolean? = null,
    val emergencyServicesContacted: Boolean? = null,
    val recoveryDurationMinutes: Int? = null,
    val locationIncluded: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val peakTriggerScore: Float? = null,
    val accelMagnitude: Float? = null,
    val gyroMagnitude: Float? = null,
    val heartRate: Float? = null,
    val pressure: Float? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
