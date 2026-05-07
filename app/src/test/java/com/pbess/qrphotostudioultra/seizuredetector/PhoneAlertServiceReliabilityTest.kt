package com.pbess.qrphotostudioultra.seizuredetector

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAlertServiceReliabilityTest {

    @Test
    fun `no contacts precheck fails with NO_CONTACTS`() {
        val result = PhoneAlertService.evaluateSmsPrecheck(
            eventId = "event-1",
            contacts = emptySet(),
            hasSmsPermission = true,
            alreadyDispatchedEventIds = emptySet()
        )

        assertEquals(PhoneAlertService.SmsPrecheck.FAIL_NO_CONTACTS, result)
    }

    @Test
    fun `missing sms permission precheck fails with SMS_PERMISSION`() {
        val result = PhoneAlertService.evaluateSmsPrecheck(
            eventId = "event-1",
            contacts = setOf("+441234567890"),
            hasSmsPermission = false,
            alreadyDispatchedEventIds = emptySet()
        )

        assertEquals(PhoneAlertService.SmsPrecheck.FAIL_SMS_PERMISSION, result)
    }

    @Test
    fun `duplicate event precheck skips resend`() {
        val result = PhoneAlertService.evaluateSmsPrecheck(
            eventId = "event-1",
            contacts = setOf("+441234567890"),
            hasSmsPermission = true,
            alreadyDispatchedEventIds = setOf("event-1")
        )

        assertEquals(PhoneAlertService.SmsPrecheck.SKIP_DUPLICATE, result)
    }

    @Test
    fun `provider exception maps to sms provider category`() {
        val category = PhoneAlertService.mapSmsExceptionToFailureCategory(RuntimeException("provider"))
        assertEquals(PhoneAlertService.FAILURE_SMS_PROVIDER, category)
    }
}
