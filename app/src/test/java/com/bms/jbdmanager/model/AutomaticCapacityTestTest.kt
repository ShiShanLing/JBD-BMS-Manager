package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCapacityTestTest {
    @Test
    fun `integrates discharge current and energy using trapezoid`() {
        val start = startCapacityTest(info(soc = 100, remaining = 50.0, current = -10.0), 0L, false)
        val updated = updateCapacityTest(
            start,
            info(soc = 99, remaining = 49.8, current = -10.0),
            speedKmh = 30.0,
            nowMillis = 3_600_000L
        )

        // One-hour gaps are deliberately treated as missing data rather than integrated.
        assertEquals(0.0, updated.dischargedAh, 0.0001)
        assertEquals(0.2, updated.missingDischargeAh, 0.0001)

        val shortStart = startCapacityTest(info(current = -10.0), 0L, false)
        val short = updateCapacityTest(shortStart, info(current = -10.0), 30.0, 3_600L)
        assertEquals(0.01, short.dischargedAh, 0.0001)
        assertEquals(0.5, short.dischargedWh, 0.001)
    }

    @Test
    fun `large reconnect gap is tracked as missing capacity`() {
        val start = startCapacityTest(info(remaining = 50.0), 1_000L, true)
        val updated = updateCapacityTest(start, info(remaining = 48.5), 25.0, 61_000L)

        assertEquals(1.5, updated.missingDischargeAh, 0.0001)
        assertEquals(0.0, updated.dischargedAh, 0.0001)
        assertEquals(0.0, updated.coveragePercent, 0.0001)
    }

    @Test
    fun `full to low voltage result qualifies when coverage is complete`() {
        val completed = AutomaticCapacityTestState(
            phase = AutomaticCapacityTestPhase.Completed,
            startSocPercent = 100,
            currentSocPercent = 3,
            ratedCapacityAh = 50.0,
            dischargedAh = 47.0,
            missingDischargeAh = 1.0,
            lowVoltageReached = true
        )

        assertTrue(completed.isQualifiedForHealth)
    }

    @Test
    fun `early manual finish remains reference only`() {
        val completed = AutomaticCapacityTestState(
            phase = AutomaticCapacityTestPhase.Completed,
            startSocPercent = 100,
            currentSocPercent = 45,
            ratedCapacityAh = 50.0,
            dischargedAh = 27.0
        )

        assertFalse(completed.isQualifiedForHealth)
    }

    private fun info(
        soc: Int = 100,
        remaining: Double = 50.0,
        current: Double = -10.0
    ) = BmsBasicInfo(
        totalVoltageV = 50.0,
        currentA = current,
        remainingCapacityAh = remaining,
        nominalCapacityAh = 50.0,
        fullChargeCapacityAh = null,
        stateOfChargePercent = soc,
        cycleCount = 1,
        temperaturesC = listOf(25.0),
        cellCount = 16,
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        balancingMask = 0,
        protectionMask = 0,
        alarmMask = null,
        softwareVersion = "1.0",
        productionDate = null,
        humidityPercent = null,
        balancingCurrentMa = null
    )
}
