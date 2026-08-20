package com.bms.jbdmanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingStateTest {
    @Test
    fun stationaryCurrentAbove7AIsCharging() {
        assertTrue(isStationaryCharging(7.1, 0.0))
        assertTrue(isStationaryCharging(12.0, 0.8))
    }

    @Test
    fun movingRegenIsNotChargingEvenAbove7A() {
        assertFalse(isStationaryCharging(8.5, 12.0))
        assertFalse(isStationaryCharging(20.0, 1.0))
    }

    @Test
    fun stationaryCurrentAtOrBelow7AIsNotCharging() {
        assertFalse(isStationaryCharging(7.0, 0.0))
        assertFalse(isStationaryCharging(3.2, 0.0))
        assertFalse(isStationaryCharging(-18.0, 0.0))
    }
}
