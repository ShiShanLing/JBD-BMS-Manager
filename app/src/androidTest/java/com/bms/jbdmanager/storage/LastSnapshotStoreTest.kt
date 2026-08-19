package com.bms.jbdmanager.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastSnapshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPreviousSnapshot() {
        context.getSharedPreferences("jbd_last_snapshot", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun newerSnapshotCompletelyReplacesPreviousSnapshot() {
        val store = LastSnapshotStore(context)
        store.save(sampleState(voltage = 55.7, soc = 52, cells = listOf(3276, 3279)), nowMillis = 1_000L)
        store.save(sampleState(voltage = 54.2, soc = 47, cells = listOf(3180, 3182, 3181)), nowMillis = 2_000L)

        val restored = store.load()

        assertNotNull(restored)
        assertEquals(2_000L, restored!!.savedAtMillis)
        assertEquals(54.2, restored.basicInfo.totalVoltageV, 0.001)
        assertEquals(47, restored.basicInfo.stateOfChargePercent)
        assertEquals(listOf(3180, 3182, 3181), restored.cells?.millivolts)
    }

    private fun sampleState(voltage: Double, soc: Int, cells: List<Int>): BmsUiState = BmsUiState(
        connectedAddress = "A5:C2:39:53:FB:40",
        connectedName = "JBD BMS",
        basicInfo = BmsBasicInfo(
            totalVoltageV = voltage,
            currentA = -10.5,
            remainingCapacityAh = 24.5,
            nominalCapacityAh = 50.0,
            fullChargeCapacityAh = 49.8,
            stateOfChargePercent = soc,
            cycleCount = 2,
            temperaturesC = listOf(31.2, 31.5),
            cellCount = cells.size,
            chargeMosEnabled = true,
            dischargeMosEnabled = true,
            balancingMask = 0L,
            protectionMask = 0,
            alarmMask = null,
            softwareVersion = "8.0",
            productionDate = "2026-07-06",
            humidityPercent = null,
            balancingCurrentMa = null,
            updatedAtMillis = 900L
        ),
        cells = CellSummary(cells, updatedAtMillis = 950L)
    )
}
