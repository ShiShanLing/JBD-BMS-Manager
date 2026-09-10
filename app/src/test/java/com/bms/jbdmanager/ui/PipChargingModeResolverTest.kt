package com.bms.jbdmanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试充电
//PipChargingModeResolverTest 验证 PipChargingModeResolver 的正常流程、边界输入和需要长期保持的回归行为。
class PipChargingModeResolverTest {
    @Test
    //MARK:测画中画缩放
    //验证contentscalegrowscontinuouslywithpictureinpicturebounds场景的关键输出，防止后续修改破坏既有行为。
    fun contentScaleGrowsContinuouslyWithPictureInPictureBounds() {
        assertEquals(0.72f, resolvePipContentScale(widthDp = 160f, heightDp = 90f), 0.001f)
        assertEquals(1.0f, resolvePipContentScale(widthDp = 240f, heightDp = 135f), 0.001f)
        assertEquals(1.3f, resolvePipContentScale(widthDp = 312f, heightDp = 175.5f), 0.001f)
        assertEquals(1.55f, resolvePipContentScale(widthDp = 400f, heightDp = 225f), 0.001f)
    }

    @Test
    //MARK:测试充电布局
    //验证brakingregenerationdoesnotswitchto充电layout场景的关键输出，防止后续修改破坏既有行为。
    fun brakingRegenerationDoesNotSwitchToChargingLayout() {
        val resolver = PipChargingModeResolver()
        assertFalse(resolver.update(currentA = -18.0, speedKmh = 35.0, nowMillis = 0L))
        assertFalse(resolver.update(currentA = 20.0, speedKmh = 0.0, nowMillis = 5_000L))
        assertFalse(resolver.update(currentA = 20.0, speedKmh = 0.0, nowMillis = 14_000L))
        assertFalse(resolver.update(currentA = 0.0, speedKmh = 0.0, nowMillis = 15_000L))
    }

    @Test
    //MARK:测试充电布局
    //验证stationarystable电流eventuallyswitchesto充电layout场景的关键输出，防止后续修改破坏既有行为。
    fun stationaryStableCurrentEventuallySwitchesToChargingLayout() {
        val resolver = PipChargingModeResolver()
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 0L))
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 9_000L))
        assertTrue(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 10_000L))
    }

    @Test
    //MARK:测试充电布局
    //验证recentmovementdelays充电layoutuntilguardexpires场景的关键输出，防止后续修改破坏既有行为。
    fun recentMovementDelaysChargingLayoutUntilGuardExpires() {
        val resolver = PipChargingModeResolver()
        assertFalse(resolver.update(currentA = -10.0, speedKmh = 20.0, nowMillis = 0L))
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 1_000L))
        assertFalse(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 11_000L))
        assertTrue(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 15_000L))
    }

    @Test
    //MARK:测试布局当前
    //验证充电layoutdoesnotexitonbrief电流dip场景的关键输出，防止后续修改破坏既有行为。
    fun chargingLayoutDoesNotExitOnBriefCurrentDip() {
        val resolver = PipChargingModeResolver()
        resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 0L)
        assertTrue(resolver.update(currentA = 12.0, speedKmh = 0.0, nowMillis = 10_000L))
        assertTrue(resolver.update(currentA = 0.0, speedKmh = 0.0, nowMillis = 11_000L))
        assertFalse(resolver.update(currentA = 0.0, speedKmh = 0.0, nowMillis = 14_000L))
    }
}
