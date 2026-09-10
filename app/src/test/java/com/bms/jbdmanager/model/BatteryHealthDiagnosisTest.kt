package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试健康诊断
//BatteryHealthDiagnosisTest 验证 BatteryHealthDiagnosis 的正常流程、边界输入和需要长期保持的回归行为。
class BatteryHealthDiagnosisTest {

    @Test
    //MARK:测试单体
    //验证uniformpack电压dropisnottreatedasweak单体场景的关键输出，防止后续修改破坏既有行为。
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
    //MARK:测试单体
    //验证one单体becomingrelativelyloweris危险场景的关键输出，防止后续修改破坏既有行为。
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
    //MARK:测试容量
    //验证measured容量belowseventyfivepercentis危险场景的关键输出，防止后续修改破坏既有行为。
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
    //MARK:测试诊断
    //验证诊断staysinsufficientwithoutreal证据场景的关键输出，防止后续修改破坏既有行为。
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
    //MARK:测试温差过滤
    //验证verydifferent温度arenotcompared场景的关键输出，防止后续修改破坏既有行为。
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

    //MARK:构造满充指纹
    //构造指定日期和逐串电压的满充指纹，用于健康诊断的前后对比。
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

    //MARK:测试容量记录
    //构造一条满足健康评估条件的容量测试记录，用于验证 SOH 诊断。
    private fun capacityRecord(measured: Double, rated: Double) = CapacityHealthRecord(
        id = 1,
        recordedAtMillis = 1,
        measuredDischargeAh = measured,
        ratedCapacityAh = rated
    )
}
