package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionEventTest {
    @Test
    fun lowSocUndervoltageIsExpectedCutoff() {
        val result = classifyProtectionEvent(
            bit = 3,
            info = sampleInfo(soc = 3, currentA = -15.0),
            cells = CellSummary(listOf(2490, 2520, 2510))
        )

        assertEquals(ProtectionEventSeverity.Expected, result.severity)
    }

    @Test
    fun earlySingleCellUndervoltageWithLargeDeltaIsCritical() {
        val result = classifyProtectionEvent(
            bit = 1,
            info = sampleInfo(soc = 35, currentA = -12.0),
            cells = CellSummary(listOf(2450, 2700, 2690))
        )

        assertEquals(ProtectionEventSeverity.Critical, result.severity)
    }

    @Test
    fun highLoadUndervoltageIsRecordedAsWarning() {
        val result = classifyProtectionEvent(
            bit = 3,
            info = sampleInfo(soc = 40, currentA = -35.0),
            cells = CellSummary(listOf(2850, 2890, 2880))
        )

        assertEquals(ProtectionEventSeverity.Warning, result.severity)
    }

    @Test
    fun shortHighLoadUndervoltageIsLabeledAsVoltageSagAfterRecovery() {
        val event = ProtectionEvent(
            id = 1L,
            protectionBit = 3,
            title = "总压过低",
            startedAtMillis = 1_000L,
            severity = ProtectionEventSeverity.Warning,
            summary = "较大放电电流下触发，可能是负载瞬时压降",
            stateOfChargePercent = 40,
            totalVoltageV = 45.0,
            currentA = -35.0
        )

        val resolved = resolveProtectionEvent(event, 21_000L)

        assertEquals(21_000L, resolved.resolvedAtMillis)
        assertTrue(resolved.summary.contains("短时欠压"))
    }

    private fun sampleInfo(soc: Int, currentA: Double) = BmsBasicInfo(
        totalVoltageV = 50.0,
        currentA = currentA,
        remainingCapacityAh = 20.0,
        nominalCapacityAh = 50.0,
        fullChargeCapacityAh = null,
        stateOfChargePercent = soc,
        cycleCount = 10,
        temperaturesC = listOf(30.0),
        cellCount = 16,
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        balancingMask = 0L,
        protectionMask = 0,
        alarmMask = null,
        softwareVersion = "8.0",
        productionDate = null,
        humidityPercent = null,
        balancingCurrentMa = null
    )
}
