package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CapacityHealthRecordTest {
    @Test
    fun sohUsesRatedCapacityCapturedFromBms() {
        val record = CapacityHealthRecord(
            id = 1L,
            recordedAtMillis = 1L,
            measuredDischargeAh = 48.0,
            ratedCapacityAh = 60.0
        )

        assertEquals(80.0, record.sohPercent, 0.001)
    }
}
