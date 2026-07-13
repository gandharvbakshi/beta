package com.example.beta

import java.io.IOException

object BackendRetryPolicy {
    const val MAX_ATTEMPTS: Int = 3

    private val retryableHttpStatusCodes = setOf(408, 429, 500, 502, 503, 504)

    fun canRetryTransportFailure(attempt: Int, error: Throwable): Boolean {
        return isValidAttempt(attempt) &&
            attempt < MAX_ATTEMPTS &&
            error is IOException
    }

    fun canRetryHttpStatus(attempt: Int, statusCode: Int): Boolean {
        return isValidAttempt(attempt) &&
            attempt < MAX_ATTEMPTS &&
            isRetryableHttpStatus(statusCode)
    }

    fun isRetryableHttpStatus(statusCode: Int): Boolean {
        return statusCode in retryableHttpStatusCodes
    }

    fun nextDelayMillis(attempt: Int): Long? {
        return when (attempt) {
            1 -> 750L
            2 -> 1500L
            else -> null
        }
    }

    fun isValidAttempt(attempt: Int): Boolean {
        return attempt in 1..MAX_ATTEMPTS
    }
}
