package com.pbess.qrphotostudioultra.seizuredetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainActivityPrivacyTest {

    @Test
    fun `masked phone number does not expose full original`() {
        val original = "+441234567890"
        val masked = original.toMaskedPhoneNumber()

        assertEquals("+••••••7890", masked)
        assertFalse(masked.contains(original))
    }
}

