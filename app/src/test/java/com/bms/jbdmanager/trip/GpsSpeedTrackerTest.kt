package com.bms.jbdmanager.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsSpeedTrackerTest {
    @Test
    fun `uses five second samples and records highest stable average`() {
        val tracker = GpsSpeedTracker()

        tracker.update(20.0, 1_000L)
        tracker.update(30.0, 3_000L)
        val state = tracker.update(40.0, 5_000L)

        assertEquals(30.0, state.average5SecondsKmh, 0.001)
        assertEquals(30.0, state.maximumKmh, 0.001)
    }

    @Test
    fun `reset clears connection speed history`() {
        val tracker = GpsSpeedTracker()
        tracker.update(35.0, 1_000L)

        tracker.reset()
        val state = tracker.update(10.0, 2_000L)

        assertEquals(10.0, state.currentKmh, 0.001)
        assertEquals(10.0, state.average5SecondsKmh, 0.001)
        assertEquals(0.0, state.maximumKmh, 0.001)
    }
}
