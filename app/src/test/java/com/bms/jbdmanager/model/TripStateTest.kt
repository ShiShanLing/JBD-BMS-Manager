package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStateTest {
    @Test
    fun calculatesConsumptionAndRemainingRangeFromTripSamples() {
        val trip = TripState(
            distanceMeters = 20_000.0,
            startSocPercent = 80,
            currentSocPercent = 60,
            startRemainingAh = 40.0,
            currentRemainingAh = 30.0,
            integratedConsumedAh = 9.8,
            integratedConsumedWh = 540.0
        )

        assertEquals(20, trip.socDropPercent)
        assertEquals(10.0, trip.consumedAh, 0.0001)
        assertEquals(50.0, trip.ahPer100Km!!, 0.0001)
        assertEquals(27.0, trip.whPerKm!!, 0.0001)
        assertEquals(60.0, trip.estimatedRemainingKm!!, 0.0001)
        assertEquals("较稳定", trip.estimateConfidence)
    }

    @Test
    fun hidesRangeEstimateUntilThereIsEnoughData() {
        val trip = TripState(
            distanceMeters = 400.0,
            startRemainingAh = 30.0,
            currentRemainingAh = 29.95,
            integratedConsumedAh = 0.05
        )

        assertNull(trip.estimatedRemainingKm)
        assertNull(trip.ahPer100Km)
        assertEquals("采集中", trip.estimateConfidence)
    }

    @Test
    fun rangeTestCalculatesOnlyItsIndependentEffectiveSamples() {
        val test = RangeTestState(
            targetSpeedKmh = 40,
            effectiveDistanceMeters = 10_000.0,
            effectiveDurationSeconds = 900.0,
            consumedAh = 4.0,
            consumedWh = 220.0,
            currentRemainingAh = 24.0
        )

        assertEquals(35, test.minimumSpeedKmh)
        assertEquals(45, test.maximumSpeedKmh)
        assertEquals(40.0, test.averageSpeedKmh!!, 0.0001)
        assertEquals(40.0, test.ahPer100Km!!, 0.0001)
        assertEquals(22.0, test.whPerKm!!, 0.0001)
        assertEquals(60.0, test.estimatedRemainingKm!!, 0.0001)
        assertEquals("初步估算", test.confidence)
    }

    @Test
    fun automaticSpeedRangesDoNotOverlapForNormalSamples() {
        val ranges = defaultSpeedRangeStats()

        assertEquals(25, ranges.single { it.accepts(24.0) }.targetSpeedKmh)
        assertEquals(30, ranges.single { it.accepts(31.0) }.targetSpeedKmh)
        assertEquals(35, ranges.single { it.accepts(34.0) }.targetSpeedKmh)
        assertEquals(50, ranges.single { it.accepts(51.0) }.targetSpeedKmh)
        assertEquals(60, ranges.single { it.accepts(59.0) }.targetSpeedKmh)
    }

    @Test
    fun automaticSpeedRangeProducesIndependentEstimate() {
        val stats = SpeedRangeStats(
            targetSpeedKmh = 45,
            effectiveDistanceMeters = 10_000.0,
            effectiveDurationSeconds = 800.0,
            consumedAh = 5.0,
            consumedWh = 280.0
        )

        assertEquals(45.0, stats.averageSpeedKmh!!, 0.0001)
        assertEquals(50.0, stats.ahPer100Km!!, 0.0001)
        assertEquals(28.0, stats.whPerKm!!, 0.0001)
        assertEquals(50.0, stats.estimatedRemainingKm(25.0)!!, 0.0001)
    }
}
