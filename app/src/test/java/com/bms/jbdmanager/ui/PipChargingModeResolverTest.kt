package com.bms.jbdmanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipChargingModeResolverTest {
    @Test
    fun brakingRegenerationDoesNotSwitchToChargingLayout() {
        val resolver = PipChargingModeResolver()
        assertFalse(resolver.update(currentA = -18.0, speedKmh = 35.0, nowMillis = 0L))
        assertFalse(resolver.update(currentA = 20.0, speedKmh = 0.0, nowMillis = 5_000L))
        assertFalse(resolver.update(currentA = 20.0, speedKmh = 0.0, nowMillis = 14_000L))
        assertFalse(resolver.update(currentA = 0.0, speedKmh = 0.0, nowMillis = 15_000L))
    }

    @Test
    fun stationaryStableCurrentEventuallySwitchesToChargingLayout() {
        val resolver = PipChargingModeResolver()
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 0L))
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 9_000L))
        assertTrue(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 10_000L))
    }

    @Test
    fun recentMovementDelaysChargingLayoutUntilGuardExpires() {
        val resolver = PipChargingModeResolver()
        assertFalse(resolver.update(currentA = -10.0, speedKmh = 20.0, nowMillis = 0L))
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 1_000L))
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 11_000L))
        assertTrue(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 15_000L))
    }

    @Test
    fun chargingLayoutDoesNotExitOnBriefCurrentDip() {
        val resolver = PipChargingModeResolver()
        resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 0L)
        assertTrue(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 10_000L))
        assertTrue(resolver.update(currentA = 0.0, speedKmh = 0.0, nowMillis = 11_000L))
        assertFalse(resolver.update(currentA = 0.0, speedKmh = 0.0, nowMillis = 14_000L))
    }
}
