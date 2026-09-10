package com.bms.jbdmanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试充电状态
//ChargingStateTest 验证 ChargingState 的正常流程、边界输入和需要长期保持的回归行为。
class ChargingStateTest {
    @Test
    //MARK:测试当前充电
    //验证stationary电流above7ais充电场景的关键输出，防止后续修改破坏既有行为。
    fun stationaryCurrentAbove7AIsCharging() {
        assertTrue(isStationaryCharging(7.1, 0.0))
        assertTrue(isStationaryCharging(12.0, 0.8))
    }

    @Test
    //MARK:测试充电
    //验证movingregenisnot充电evenabove7a场景的关键输出，防止后续修改破坏既有行为。
    fun movingRegenIsNotChargingEvenAbove7A() {
        assertFalse(isStationaryCharging(8.5, 12.0))
        assertFalse(isStationaryCharging(20.0, 1.0))
    }

    @Test
    //MARK:测试当前充电
    //验证stationary电流atorbelow7aisnot充电场景的关键输出，防止后续修改破坏既有行为。
    fun stationaryCurrentAtOrBelow7AIsNotCharging() {
        assertFalse(isStationaryCharging(7.0, 0.0))
        assertFalse(isStationaryCharging(3.2, 0.0))
        assertFalse(isStationaryCharging(-18.0, 0.0))
    }
}
