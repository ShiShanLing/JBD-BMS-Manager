package com.bms.jbdmanager.safety

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.TemperatureAlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试温度安全
//TemperatureSafetyMonitorTest 验证 TemperatureSafetyMonitor 的正常流程、边界输入和需要长期保持的回归行为。
class TemperatureSafetyMonitorTest {
    @Test
    //MARK:测试温度
    //验证警告需要repeated大温度样本场景的关键输出，防止后续修改破坏既有行为。
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
    //MARK:测试温度警报
    //验证single温度spikedoesnottriggeralert场景的关键输出，防止后续修改破坏既有行为。
    fun singleTemperatureSpikeDoesNotTriggerAlert() {
        val monitor = TemperatureSafetyMonitor()

        assertNull(monitor.update(info(70.0), null, 1_000L).alert)
        assertNull(monitor.update(info(35.0), null, 2_000L).alert)
    }

    @Test
    //MARK:测试温度保护
    //验证bms大温度保护triggers危险immediately场景的关键输出，防止后续修改破坏既有行为。
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
    //MARK:测试警报温度
    //验证alert清除onlyafter温度staysbelow恢复threshold场景的关键输出，防止后续修改破坏既有行为。
    fun alertClearsOnlyAfterTemperatureStaysBelowRecoveryThreshold() {
        val monitor = TemperatureSafetyMonitor()
        repeat(3) { index -> monitor.update(info(55.0), null, 1_000L + index * 1_000L) }

        repeat(4) { index ->
            assertFalse(monitor.update(info(50.0), null, 5_000L + index * 1_000L).recovered)
        }
        assertTrue(monitor.update(info(50.0), null, 9_000L).recovered)
    }

    @Test
    //MARK:测试十秒温升
    //验证tensecond窗口detects快速升温withoutsixtyseconddilution场景的关键输出，防止后续修改破坏既有行为。
    fun tenSecondWindowDetectsRapidRiseWithoutSixtySecondDilution() {
        val monitor = TemperatureSafetyMonitor()
        monitor.update(info(40.0), null, 0L)
        monitor.update(info(40.0), null, 50_000L)
        assertNull(monitor.update(info(42.0), null, 58_000L).alert)
        assertNull(monitor.update(info(42.2), null, 59_000L).alert)

        val alert = monitor.update(info(42.4), null, 60_000L).alert

        assertEquals(TemperatureAlertLevel.Warning, alert?.level)
        assertEquals(10, alert?.riseWindowSeconds)
        assertTrue((alert?.riseRateCPerMinute ?: 0.0) >= 8.0)
        assertTrue(alert?.message?.contains("近10秒升温速度") == true)
    }

    @Test
    //MARK:测试短时温升
    //验证升温shorterthanminimum窗口doesnottriggeronitsown场景的关键输出，防止后续修改破坏既有行为。
    fun riseShorterThanMinimumWindowDoesNotTriggerOnItsOwn() {
        val monitor = TemperatureSafetyMonitor()
        monitor.update(info(43.0), null, 1_000L)
        monitor.update(info(46.0), null, 5_000L)

        assertNull(monitor.update(info(46.5), null, 7_000L).alert)
    }

    //MARK:构造电池信息
    //构造带指定关键字段的 BMS 基本信息，其他字段使用稳定默认值以突出当前断言。
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
