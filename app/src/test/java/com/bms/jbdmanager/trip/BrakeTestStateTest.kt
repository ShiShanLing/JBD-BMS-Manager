package com.bms.jbdmanager.trip

import com.bms.jbdmanager.model.BrakeTestPhase
import com.bms.jbdmanager.model.BrakeTestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrakeTestStateTest {
    @Test
    fun completesFromTargetSpeedToStop() {
        var state = BrakeTestState(targetSpeedKmh = 40, phase = BrakeTestPhase.Armed)
        state = TripTracker.updateBrakeTest(state, 39.0, 1_000L, 1.5)
        state = TripTracker.updateBrakeTest(state, 42.0, 2_000L, 1.5)
        assertEquals(BrakeTestPhase.Ready, state.phase)

        state = TripTracker.updateBrakeTest(state, 38.0, 3_000L, 1.5)
        assertEquals(BrakeTestPhase.Braking, state.phase)
        state = TripTracker.updateBrakeTest(state, 20.0, 4_000L, 1.5)
        state = TripTracker.updateBrakeTest(state, 1.0, 5_000L, 1.5)
        state = TripTracker.updateBrakeTest(state, 0.0, 6_000L, 1.5)

        assertEquals(BrakeTestPhase.Complete, state.phase)
        assertTrue(state.brakingDistanceMeters > 0.0)
        assertTrue(state.brakingDurationSeconds > 0.0)
        assertTrue((state.averageDecelerationMps2 ?: 0.0) > 0.0)
    }

    @Test
    fun rejectsInterruptedGpsSampleStream() {
        var state = BrakeTestState(targetSpeedKmh = 40, phase = BrakeTestPhase.Armed)
        state = TripTracker.updateBrakeTest(state, 35.0, 1_000L, 2.0)
        state = TripTracker.updateBrakeTest(state, 36.0, 5_500L, 2.0)

        assertEquals(BrakeTestPhase.Failed, state.phase)
    }
}
