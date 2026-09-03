package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullChargeDeltaTrendTest {

    @Test
    fun emptySamplesNeedMoreData() {
        val result = evaluateFullChargeDeltaTrend(emptyList())
        assertEquals(FullChargeDeltaDirection.Insufficient, result.direction)
        assertNull(result.latestDeltaMv)
    }

    @Test
    fun oneSampleIsNotEnoughToJudge() {
        val result = evaluateFullChargeDeltaTrend(listOf(sample(0, 28)))
        assertEquals(FullChargeDeltaDirection.Insufficient, result.direction)
        assertEquals(28, result.latestDeltaMv)
    }

    @Test
    fun shrinkingDeltaIsImproving() {
        val result = evaluateFullChargeDeltaTrend(
            listOf(sample(0, 32), sample(30, 20))
        )
        assertEquals(FullChargeDeltaDirection.Improving, result.direction)
        assertEquals(-12.0, result.changeMv!!, 0.01)
        assertTrue(result.summary.contains("向好"))
    }

    @Test
    fun growingDeltaIsWorsening() {
        val result = evaluateFullChargeDeltaTrend(
            listOf(sample(0, 18), sample(30, 30))
        )
        assertEquals(FullChargeDeltaDirection.Worsening, result.direction)
        assertTrue(result.summary.contains("变差"))
    }

    @Test
    fun smallChangeStaysStable() {
        val result = evaluateFullChargeDeltaTrend(
            listOf(sample(0, 22), sample(14, 24))
        )
        assertEquals(FullChargeDeltaDirection.Stable, result.direction)
    }

    private fun sample(day: Long, deltaMv: Int) = FullChargeDeltaSample(
        capturedAtMillis = day * 24 * 60 * 60 * 1_000L,
        cellDeltaMv = deltaMv,
        totalVoltageV = 55.8
    )
}
