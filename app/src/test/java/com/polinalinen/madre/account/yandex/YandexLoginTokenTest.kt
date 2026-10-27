package com.polinalinen.madre.account.yandex

import org.junit.Assert.*
import org.junit.Test

class YandexLoginTokenTest {
    @Test fun tokenHasIdentitySemanticsAndNeverPrintsItsSyntheticValue() {
        val value="SYNTHETIC_TEST_ONLY_BEARER"
        val token=YandexLoginToken.takeSdkResult(value)
        assertEquals(value,token.forVerificationRequest())
        assertFalse(token.toString().contains(value))
        assertFalse((token as Any) is java.io.Serializable)
        token.close()
        assertTrue(token.isClosed)
        token.close()
        assertThrows(IllegalStateException::class.java){token.forVerificationRequest()}
    }

    @Test fun invalidInputNeverLeaksIntoTheExceptionMessage() {
        val error=assertThrows(IllegalArgumentException::class.java){YandexLoginToken.takeSdkResult(" \n ")}
        assertEquals("Empty login result",error.message)
    }
}
