package com.bms.jbdmanager.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.CellSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryTrendStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearDatabase() {
        context.deleteDatabase("battery_trends.db")
    }

    @Test
    fun samplesAreSeparatedByDeviceAndReturnedInTimeOrder() {
        val store = BatteryTrendStore(context)
        val now = System.currentTimeMillis()
        store.insert("AA", point(now - 20_000, 54.2))
        store.insert("BB", point(now - 15_000, 40.0))
        store.insert("AA", point(now - 10_000, 53.8))

        val loaded = store.query("AA", now - 30_000, now)

        assertEquals(2, loaded.size)
        assertEquals(54.2, loaded.first().totalVoltageV, 0.001)
        assertEquals(53.8, loaded.last().totalVoltageV, 0.001)
    }

    @Test
    fun fullChargeFingerprintKeepsHighestPackVoltageForDay() {
        val store = BatteryTrendStore(context)
        val cells = CellSummary(listOf(3490, 3492, 3488))
        store.recordFullChargeFingerprint("AA", info(55.6), cells)
        store.recordFullChargeFingerprint("AA", info(55.4), CellSummary(listOf(3450, 3452, 3448)))
        store.recordFullChargeFingerprint("AA", info(55.8), CellSummary(listOf(3494, 3496, 3492)))

        val loaded = store.loadFullChargeFingerprints("AA")

        assertEquals(1, loaded.size)
        assertEquals(55.8, loaded.single().totalVoltageV, 0.001)
        assertEquals(listOf(3494, 3496, 3492), loaded.single().cellVoltagesMv)
    }

    @Test
    fun nonFullBatteryDoesNotCreateFingerprint() {
        val store = BatteryTrendStore(context)
        store.recordFullChargeFingerprint("AA", info(53.0, soc = 90), CellSummary(listOf(3310, 3312)))
        assertTrue(store.loadFullChargeFingerprints("AA").isEmpty())
    }

    @Test
    fun veryOldDetailedSampleBecomesPermanentDailySummaryBeforeCleanup() {
        val store = BatteryTrendStore(context)
        val now = System.currentTimeMillis()
        val old = now - 400L * 24 * 60 * 60 * 1_000
        store.insert("AA", point(old, 52.5))

        store.maintain(now)

        assertTrue(store.query("AA", old - 1_000, old + 1_000).isEmpty())
        val dailyCount = context.openOrCreateDatabase("battery_trends.db", 0, null).use { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM trend_daily WHERE device_address = ?",
                arrayOf("AA")
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }
        assertEquals(1, dailyCount)
    }

    @Test
    fun fullChargeDeltaRecordsNearFullSocOrAhAndDebouncesReconnects() {
        val store = BatteryTrendStore(context)
        val now = 1_700_000_000_000L
        store.recordFullChargeDelta("AA", info(55.6, soc = 90), CellSummary(listOf(3490, 3470)), now)
        store.recordFullChargeDelta("AA", info(55.8, soc = 99), CellSummary(listOf(3496, 3472)), now)
        store.recordFullChargeDelta("AA", info(55.9, soc = 100), CellSummary(listOf(3498, 3470)), now + 30 * 60 * 1_000L)
        store.recordFullChargeDelta(
            "AA",
            info(55.7, soc = 98, remainingAh = 49.9),
            CellSummary(listOf(3494, 3478)),
            now + 3 * 60 * 60 * 1_000L
        )

        val loaded = store.loadFullChargeDeltas("AA")

        assertEquals(2, loaded.size)
        assertEquals(24, loaded.first().cellDeltaMv)
        assertEquals(99, loaded.first().socPercent)
        assertEquals(50.0, loaded.first().remainingCapacityAh!!, 0.001)
        assertEquals(16, loaded.last().cellDeltaMv)
        assertEquals(98, loaded.last().socPercent)
        assertEquals(49.9, loaded.last().remainingCapacityAh!!, 0.001)
        assertTrue(store.loadFullChargeDeltas("BB").isEmpty())
    }

    private fun point(time: Long, voltage: Double) = BatteryTrendPoint(
        timestampMillis = time,
        totalVoltageV = voltage,
        currentA = -12.0,
        socPercent = 70.0,
        maximumTemperatureC = 33.0,
        cellDeltaMv = 4.0,
        minimumCellMv = 3275.0
    )

    private fun info(voltage: Double, soc: Int = 100, remainingAh: Double? = null) = BmsBasicInfo(
        totalVoltageV = voltage,
        currentA = 0.2,
        remainingCapacityAh = remainingAh ?: if (soc >= 99) 50.0 else soc / 100.0 * 50.0,
        nominalCapacityAh = 50.0,
        fullChargeCapacityAh = 50.0,
        stateOfChargePercent = soc,
        cycleCount = 3,
        temperaturesC = listOf(31.0),
        cellCount = 3,
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        balancingMask = 0,
        protectionMask = 0,
        alarmMask = 0,
        softwareVersion = "8.0",
        productionDate = null,
        humidityPercent = null,
        balancingCurrentMa = null
    )
}
