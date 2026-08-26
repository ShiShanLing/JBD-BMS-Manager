package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHealthDiagnosisTest {

    @Test
    fun uniformPackVoltageDropIsNotTreatedAsWeakCell() {
        val result = diagnoseBatteryHealth(
            capacityRecords = emptyList(),
            fingerprints = listOf(
                fingerprint(day = 0, cells = listOf(3490, 3492, 3488)),
                fingerprint(day = 30, cells = listOf(3450, 3452, 3448))
            ),
            protectionEvents = emptyList()
        )

        assertTrue(result.cells.all { it.level == HealthDiagnosisLevel.Normal })
        assertTrue(result.cells.all { kotlin.math.abs(it.relativeDriftMv) < 0.01 })
    }

    @Test
    fun oneCellBecomingRelativelyLowerIsCritical() {
        val result = diagnoseBatteryHealth(
            capacityRecords = emptyList(),
            fingerprints = listOf(
                fingerprint(day = 0, cells = listOf(3490, 3490, 3490)),
                fingerprint(day = 30, cells = listOf(3490, 3490, 3380))
            ),
            protectionEvents = emptyList()
        )

        val third = result.cells.first { it.cellNumber == 3 }
        assertEquals(HealthDiagnosisLevel.Critical, third.level)
        assertEquals(HealthDiagnosisLevel.Critical, result.overallLevel)
    }

    @Test
    fun measuredCapacityBelowSeventyFivePercentIsCritical() {
        val result = diagnoseBatteryHealth(
            capacityRecords = listOf(capacityRecord(37.0, 50.0)),
            fingerprints = emptyList(),
            protectionEvents = emptyList()
        )

        assertEquals(HealthDiagnosisLevel.Critical, result.overallLevel)
        assertTrue(result.findings.any { it.title.contains("74") })
    }

    @Test
    fun diagnosisStaysInsufficientWithoutRealEvidence() {
        val result = diagnoseBatteryHealth(
            capacityRecords = emptyList(),
            fingerprints = emptyList(),
            protectionEvents = emptyList()
        )

        assertEquals(HealthDiagnosisLevel.Insufficient, result.overallLevel)
        assertEquals(HealthDiagnosisConfidence.Low, result.confidence)
    }

    @Test
    fun veryDifferentTemperaturesAreNotCompared() {
        val result = diagnoseBatteryHealth(
            capacityRecords = emptyList(),
            fingerprints = listOf(
                fingerprint(day = 0, cells = listOf(3490, 3490, 3490), temperature = 20.0),
                fingerprint(day = 30, cells = listOf(3490, 3490, 3380), temperature = 40.0)
            ),
            protectionEvents = emptyList()
        )

        assertTrue(result.cells.isEmpty())
        assertEquals(1, result.comparableFingerprintCount)
    }

    private fun fingerprint(
        day: Int,
        cells: List<Int>,
        temperature: Double = 30.0
    ) = FullChargeFingerprint(
        capturedAtMillis = day * 24L * 60 * 60 * 1_000,
        totalVoltageV = cells.sum() / 1_000.0,
        socPercent = 100,
        maximumTemperatureC = temperature,
        cellVoltagesMv = cells
    )

    private fun capacityRecord(measured: Double, rated: Double) = CapacityHealthRecord(
        id = 1,
        recordedAtMillis = 1,
        measuredDischargeAh = measured,
        ratedCapacityAh = rated
    )
}
