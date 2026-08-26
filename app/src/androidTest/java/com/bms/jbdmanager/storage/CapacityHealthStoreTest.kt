package com.bms.jbdmanager.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.CapacityHealthRecord
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapacityHealthStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearRecords() {
        context.getSharedPreferences("jbd_capacity_health", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun recordsSurviveReloadAndCanBeDeleted() {
        val store = CapacityHealthStore(context)
        store.add(CapacityHealthRecord(1L, 1_000L, 45.0, 50.0, 2_300.0, 10, 28.5, "第一次"))
        store.add(CapacityHealthRecord(2L, 2_000L, 48.0, 60.0, null, 20, null, "第二次"))

        val restored = CapacityHealthStore(context).load()

        assertEquals(listOf(2L, 1L), restored.map { it.id })
        assertEquals(80.0, restored.first().sohPercent, 0.001)
        assertEquals(60.0, restored.first().ratedCapacityAh, 0.001)
        assertEquals("第一次", restored.last().note)
        assertEquals(listOf(2L), store.delete(1L).map { it.id })
    }
}
