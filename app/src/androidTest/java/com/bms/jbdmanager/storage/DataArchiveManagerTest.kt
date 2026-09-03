package com.bms.jbdmanager.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.DataExportSnapshot
import com.bms.jbdmanager.model.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DataArchiveManagerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearData() {
        context.deleteDatabase("battery_trends.db")
        listOf(
            "jbd_bms_preferences", "jbd_last_snapshot", "jbd_trip_tracking",
            "jbd_mileage_history", "jbd_capacity_health", "jbd_protection_events", "jbd_app_update"
        ).forEach { name -> context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test
    fun completeBackupRestoresPreferencesAndTrendDatabase() {
        val store = BatteryTrendStore(context)
        val manager = DataArchiveManager(context, store)
        val now = System.currentTimeMillis()
        store.insert("AA", point(now, 54.2))
        context.getSharedPreferences("jbd_bms_preferences", 0).edit()
            .putString("last_device_address", "AA")
            .putStringSet("saved_device_addresses", setOf("AA", "BB"))
            .commit()
        val output = ByteArrayOutputStream()
        manager.createFullBackup(output)

        store.insert("AA", point(now + 10_000, 53.8))
        context.getSharedPreferences("jbd_bms_preferences", 0).edit()
            .putString("last_device_address", "CHANGED")
            .commit()

        val prepared = manager.prepareRestore(ByteArrayInputStream(output.toByteArray()))
        assertEquals(1, prepared.preview.trendSampleCount)
        manager.restore(prepared)

        assertEquals(
            "AA",
            context.getSharedPreferences("jbd_bms_preferences", 0)
                .getString("last_device_address", null)
        )
        assertEquals(
            setOf("AA", "BB"),
            context.getSharedPreferences("jbd_bms_preferences", 0)
                .getStringSet("saved_device_addresses", emptySet())
        )
        val restored = store.query("AA", now - 1_000, now + 20_000)
        assertEquals(1, restored.size)
        assertEquals(54.2, restored.single().totalVoltageV, 0.001)
    }

    @Test
    fun csvPackageContainsSeparateEvidenceTables() {
        val store = BatteryTrendStore(context)
        val manager = DataArchiveManager(context, store)
        store.insert("AA", point(System.currentTimeMillis(), 54.2))
        val output = ByteArrayOutputStream()

        manager.exportCsvPackage(
            output,
            DataExportSnapshot(emptyList(), emptyList(), emptyList(), TripState())
        )

        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue(names.contains("趋势明细.csv"))
        assertTrue(names.contains("每日趋势摘要.csv"))
        assertTrue(names.contains("满充逐串电压.csv"))
        assertTrue(names.contains("满充压差.csv"))
        assertTrue(names.contains("容量测试.csv"))
        assertTrue(names.contains("保护告警.csv"))
        assertTrue(names.contains("骑行记录.csv"))
        assertTrue(names.contains("分速度续航样本.csv"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidArchiveIsRejectedBeforeRestore() {
        val manager = DataArchiveManager(context, BatteryTrendStore(context))
        manager.prepareRestore(ByteArrayInputStream("not a backup".toByteArray()))
    }

    private fun point(timestamp: Long, voltage: Double) = BatteryTrendPoint(
        timestampMillis = timestamp,
        totalVoltageV = voltage,
        currentA = -10.0,
        socPercent = 70.0,
        maximumTemperatureC = 32.0,
        cellDeltaMv = 5.0,
        minimumCellMv = 3270.0
    )
}
