package com.bms.jbdmanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试满充充电
//FullChargeDetectionTest 验证 FullChargeDetection 的正常流程、边界输入和需要长期保持的回归行为。
class FullChargeDetectionTest {
    @Test
    //MARK:测试满充
    //验证soc99countsas满充场景的关键输出，防止后续修改破坏既有行为。
    fun soc99CountsAsFull() {
        assertTrue(info(soc = 99, remaining = 49.5).isEffectivelyFullyCharged())
        assertTrue(info(soc = 100, remaining = 50.0).isEffectivelyFullyCharged())
    }

    @Test
    //MARK:测试满充
    //验证remainingahnearratedcountsas满充evenifsocislower场景的关键输出，防止后续修改破坏既有行为。
    fun remainingAhNearRatedCountsAsFullEvenIfSocIsLower() {
        assertTrue(info(soc = 98, remaining = 49.9).isEffectivelyFullyCharged())
        assertTrue(info(soc = 98, remaining = 49.5).isEffectivelyFullyCharged())
    }

    @Test
    //MARK:测试充电满充
    //验证mid充电isnot满充场景的关键输出，防止后续修改破坏既有行为。
    fun midChargeIsNotFull() {
        assertFalse(info(soc = 90, remaining = 45.0).isEffectivelyFullyCharged())
        assertFalse(info(soc = 80, remaining = 40.0).isEffectivelyFullyCharged())
    }

    //MARK:构造电池信息
    //构造带指定关键字段的 BMS 基本信息，其他字段使用稳定默认值以突出当前断言。
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
