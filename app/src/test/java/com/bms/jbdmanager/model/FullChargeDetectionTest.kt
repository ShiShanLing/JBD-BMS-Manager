package com.bms.jbdmanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullChargeDetectionTest {
    @Test
    fun soc99CountsAsFull() {
        assertTrue(info(soc = 99, remaining = 49.5).isEffectivelyFullyCharged())
        assertTrue(info(soc = 100, remaining = 50.0).isEffectivelyFullyCharged())
    }

    @Test
    fun remainingAhNearRatedCountsAsFullEvenIfSocIsLower() {
        assertTrue(info(soc = 98, remaining = 49.9).isEffectivelyFullyCharged())
        assertTrue(info(soc = 98, remaining = 49.5).isEffectivelyFullyCharged())
    }

    @Test
    fun midChargeIsNotFull() {
        assertFalse(info(soc = 90, remaining = 45.0).isEffectivelyFullyCharged())
        assertFalse(info(soc = 80, remaining = 40.0).isEffectivelyFullyCharged())
    }

    private fun info(soc: Int, remaining: Double) = BmsBasicInfo(
        totalVoltageV = 55.8,
        currentA = 0.2,
        remainingCapacityAh = remaining,
        nominalCapacityAh = 50.0,
        fullChargeCapacityAh = 50.0,
        stateOfChargePercent = soc,
        cycleCount = 3,
        temperaturesC = listOf(31.0),
        cellCount = 17,
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        balancingMask = 0,
        protectionMask = 0,
        alarmMask = 0,
        softwareVersion = "8.0",
        productionDate = null,
        humidityPercent = null,
        balancingCurrentMa = null
    )
}
