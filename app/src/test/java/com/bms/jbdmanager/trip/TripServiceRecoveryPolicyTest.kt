package com.bms.jbdmanager.trip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试行程
//TripServiceRecoveryPolicyTest 验证 TripServiceRecoveryPolicy 的正常流程、边界输入和需要长期保持的回归行为。
class TripServiceRecoveryPolicyTest {
    @Test
    fun `persisted active trip restarts a missing service`() {
        assertTrue(
            shouldAttemptTripServiceStart(
                tripIsTracking = true,
                serviceIsRunning = false,
                nowMillis = 10_000L,
                lastAttemptAtMillis = 0L,
                retryIntervalMillis = 5_000L
            )
        )
    }

    @Test
    fun `running service and retry window prevent duplicate starts`() {
        assertFalse(shouldAttemptTripServiceStart(true, true, 10_000L, 0L, 5_000L))
        assertFalse(shouldAttemptTripServiceStart(true, false, 12_000L, 10_000L, 5_000L))
    }
}
