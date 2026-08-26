package com.bms.jbdmanager.safety

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.TemperatureAlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureSafetyMonitorTest {
    @Test
    fun warningRequiresRepeatedHighTemperatureSamples() {
        val monitor = TemperatureSafetyMonitor()
        val params = JbdProtectionParams(dischargeHighTempC = 60.0)

        assertNull(monitor.update(info(55.0), params, 1_000L).alert)
        assertNull(monitor.update(info(55.0), params, 2_000L).alert)
        val alert = monitor.update(info(55.0), params, 3_000L).alert

        assertEquals(TemperatureAlertLevel.Warning, alert?.level)
        assertEquals(55.0, alert?.warningThresholdC ?: 0.0, 0.001)
    }

    @Test
    fun singleTemperatureSpikeDoesNotTriggerAlert() {
        val monitor = TemperatureSafetyMonitor()

        assertNull(monitor.update(info(70.0), null, 1_000L).alert)
        assertNull(monitor.update(info(35.0), null, 2_000L).alert)
    }

    @Test
    fun bmsHighTemperatureProtectionTriggersCriticalImmediately() {
        val monitor = TemperatureSafetyMonitor()

        val alert = monitor.update(
            info(maximumTemperatureC = 52.0, protectionMask = 1 shl 6),
            null,
            1_000L
        ).alert

        assertEquals(TemperatureAlertLevel.Critical, alert?.level)
        assertTrue(alert?.message?.contains("BMS 已触发高温保护") == true)
    }

    @Test
    fun alertClearsOnlyAfterTemperatureStaysBelowRecoveryThreshold() {
        val monitor = TemperatureSafetyMonitor()
        repeat(3) { index -> monitor.update(info(55.0), null, 1_000L + index * 1_000L) }

        repeat(4) { index ->
            assertFalse(monitor.update(info(50.0), null, 5_000L + index * 1_000L).recovered)
        }
        assertTrue(monitor.update(info(50.0), null, 9_000L).recovered)
    }

    private fun info(maximumTemperatureC: Double, protectionMask: Int = 0) = BmsBasicInfo(
        totalVoltageV = 52.0,
        currentA = -15.0,
        remainingCapacityAh = 25.0,
        nominalCapacityAh = 50.0,
        fullChargeCapacityAh = null,
        stateOfChargePercent = 50,
        cycleCount = 10,
        temperaturesC = listOf(30.0, maximumTemperatureC),
        cellCount = 16,
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        balancingMask = 0L,
        protectionMask = protectionMask,
        alarmMask = null,
        softwareVersion = "8.0",
        productionDate = null,
        humidityPercent = null,
        balancingCurrentMa = null
    )
}
