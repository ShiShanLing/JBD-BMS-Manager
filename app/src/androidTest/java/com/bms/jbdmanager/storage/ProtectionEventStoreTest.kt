package com.bms.jbdmanager.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.ProtectionEventSeverity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectionEventStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearEvents() {
        context.getSharedPreferences("jbd_protection_events", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun activeEventCanBePersistedAndResolved() {
        val store = ProtectionEventStore(context)
        val active = ProtectionEvent(
            id = 101L,
            protectionBit = 1,
            title = "单体欠压",
            startedAtMillis = 1_000L,
            severity = ProtectionEventSeverity.Critical,
            summary = "测试",
            stateOfChargePercent = 30,
            totalVoltageV = 48.0,
            currentA = -20.0,
            minimumCellMv = 2500,
            maximumCellMv = 2700,
            cellDeltaMv = 200,
            maximumTemperatureC = 35.0,
            deviceAddress = "AA:BB",
            deviceName = "JBD"
        )
        store.replace(listOf(active))

        val loaded = ProtectionEventStore(context).load().single()
        assertEquals(true, loaded.isActive)
        assertEquals(200, loaded.cellDeltaMv)

        store.replace(listOf(loaded.copy(resolvedAtMillis = 5_000L)))
        assertEquals(5_000L, store.load().single().resolvedAtMillis)
    }
}
