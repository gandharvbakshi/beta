package com.example.beta

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRetryPolicyTest {
    @Test
    fun retryableHttpStatuses_areAllowedWhileAttemptsRemain() {
        val retryableCodes = listOf(408, 429, 500, 502, 503, 504)

        retryableCodes.forEach { code ->
            assertTrue(BackendRetryPolicy.isRetryableHttpStatus(code))
            assertTrue(BackendRetryPolicy.canRetryHttpStatus(1, code))
            assertTrue(BackendRetryPolicy.canRetryHttpStatus(2, code))
        }
    }

    @Test
    fun nonRetryableHttpStatuses_coverAuthAndValidationFailures() {
        val nonRetryableCodes = listOf(400, 401, 402, 403, 404, 405, 409, 410, 412, 415, 422)

        nonRetryableCodes.forEach { code ->
            assertFalse("Status $code should not be retryable", BackendRetryPolicy.isRetryableHttpStatus(code))
            assertFalse("Status $code should not retry on attempt 1", BackendRetryPolicy.canRetryHttpStatus(1, code))
            assertFalse("Status $code should not retry on attempt 2", BackendRetryPolicy.canRetryHttpStatus(2, code))
        }
    }

    @Test
    fun transportIOException_retriesOnlyWhileAttemptsRemain() {
        val error = IOException("socket closed")

        assertTrue(BackendRetryPolicy.canRetryTransportFailure(1, error))
        assertTrue(BackendRetryPolicy.canRetryTransportFailure(2, error))
        assertFalse(BackendRetryPolicy.canRetryTransportFailure(3, error))
    }

    @Test
    fun transportNonIOException_doesNotRetry() {
        assertFalse(BackendRetryPolicy.canRetryTransportFailure(1, IllegalStateException("bad state")))
    }

    @Test
    fun nextDelayMillis_returns750Then1500ThenNull() {
        assertEquals(750L, BackendRetryPolicy.nextDelayMillis(1))
        assertEquals(1500L, BackendRetryPolicy.nextDelayMillis(2))
        assertNull(BackendRetryPolicy.nextDelayMillis(3))
    }

    @Test
    fun invalidAttempts_areRejected() {
        assertFalse(BackendRetryPolicy.isValidAttempt(0))
        assertFalse(BackendRetryPolicy.isValidAttempt(-1))
        assertFalse(BackendRetryPolicy.isValidAttempt(4))

        assertFalse(BackendRetryPolicy.canRetryTransportFailure(0, IOException()))
        assertFalse(BackendRetryPolicy.canRetryHttpStatus(0, 500))
        assertNull(BackendRetryPolicy.nextDelayMillis(0))
    }
}
