package com.pbess.qrphotostudioultra.seizuredetector

/**
 * Represents a single detected alert event.
 * Not yet persisted — designed as the future Room entity for Phase 1.
 */
data class AlertEvent(
    val eventId: String,
    val detectedAt: Long,
    val detectedBy: String,          // "watch" or "phone"
    val alertState: AlertState = AlertState.PENDING,
    val countdownStartedAt: Long? = null,
    val cancelledAt: Long? = null,
    val smsAttemptedAt: Long? = null,
    val smsSendResult: SmsSendResult? = null,
    val locationIncluded: Boolean = false
) {
    enum class AlertState {
        PENDING,
        COUNTDOWN,
        CANCELLED,
        SMS_SENT,
        SMS_FAILED
    }

    enum class SmsSendResult {
        SUCCESS,
        PARTIAL_FAILURE,
        ALL_FAILED,
        NOT_ATTEMPTED
    }
}
