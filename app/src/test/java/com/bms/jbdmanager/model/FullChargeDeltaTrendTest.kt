package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试压差趋势
//FullChargeDeltaTrendTest 验证 FullChargeDeltaTrend 的正常流程、边界输入和需要长期保持的回归行为。
class FullChargeDeltaTrendTest {

    @Test
    //MARK:测试数据
    //验证空样本needmore数据场景的关键输出，防止后续修改破坏既有行为。
    fun emptySamplesNeedMoreData() {
        val result = evaluateFullChargeDeltaTrend(emptyList())
        assertEquals(FullChargeDeltaDirection.Insufficient, result.direction)
        assertNull(result.latestDeltaMv)
    }

    @Test
    //MARK:测试样本
    //验证onesampleisnotenoughtojudge场景的关键输出，防止后续修改破坏既有行为。
    fun oneSampleIsNotEnoughToJudge() {
        val result = evaluateFullChargeDeltaTrend(listOf(sample(0, 28)))
        assertEquals(FullChargeDeltaDirection.Insufficient, result.direction)
        assertEquals(28, result.latestDeltaMv)
    }

    @Test
    //MARK:测试压差
    //验证shrinking压差isimproving场景的关键输出，防止后续修改破坏既有行为。
    fun shrinkingDeltaIsImproving() {
        val result = evaluateFullChargeDeltaTrend(
            listOf(sample(0, 32), sample(30, 20))
        )
        assertEquals(FullChargeDeltaDirection.Improving, result.direction)
        assertEquals(-12.0, result.changeMv!!, 0.01)
        assertTrue(result.summary.contains("向好"))
    }

    @Test
    //MARK:测试压差
    //验证growing压差isworsening场景的关键输出，防止后续修改破坏既有行为。
    fun growingDeltaIsWorsening() {
        val result = evaluateFullChargeDeltaTrend(
            listOf(sample(0, 18), sample(30, 30))
        )
        assertEquals(FullChargeDeltaDirection.Worsening, result.direction)
        assertTrue(result.summary.contains("变差"))
    }

    @Test
    //MARK:测试压差稳定
    //验证smallchangestaysstable场景的关键输出，防止后续修改破坏既有行为。
    fun smallChangeStaysStable() {
        val result = evaluateFullChargeDeltaTrend(
            listOf(sample(0, 22), sample(14, 24))
        )
        assertEquals(FullChargeDeltaDirection.Stable, result.direction)
    }

    //MARK:处理样本
    //构造指定时间和压差的满充样本，供趋势方向与变化量计算使用。
    private fun sample(day: Long, deltaMv: Int) = FullChargeDeltaSample(
        capturedAtMillis = day * 24 * 60 * 60 * 1_000L,
        cellDeltaMv = deltaMv,
        totalVoltageV = 55.8
    )
}
