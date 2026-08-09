package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class MultiItemTimeoutPolicyTest {
    @Test
    fun oneItem_isClampedToMinimumTenMinutes() {
        assertEquals(TimeUnit.MINUTES.toMillis(10), MultiItemTimeoutPolicy.computeTimeoutMs(1))
    }

    @Test
    fun sevenItems_usesComputedTimeout() {
        assertEquals(TimeUnit.SECONDS.toMillis(945), MultiItemTimeoutPolicy.computeTimeoutMs(7))
    }

    @Test
    fun twelveItems_usesComputedTimeout() {
        assertEquals(TimeUnit.SECONDS.toMillis(1620), MultiItemTimeoutPolicy.computeTimeoutMs(12))
    }

    @Test
    fun twentyItems_usesComputedTimeout() {
        assertEquals(TimeUnit.SECONDS.toMillis(2700), MultiItemTimeoutPolicy.computeTimeoutMs(20))
    }

    @Test
    fun twentyFiveItems_usesComputedTimeout() {
        assertEquals(TimeUnit.SECONDS.toMillis(3375), MultiItemTimeoutPolicy.computeTimeoutMs(25))
    }

    @Test
    fun veryLargeItemCounts_areClampedToMaximumSixtyMinutes() {
        assertEquals(TimeUnit.MINUTES.toMillis(60), MultiItemTimeoutPolicy.computeTimeoutMs(100))
    }
}
