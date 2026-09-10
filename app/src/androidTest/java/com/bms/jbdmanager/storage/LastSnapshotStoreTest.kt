package com.bms.jbdmanager.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.MileageHistoryState
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.RegenerationPeak
import com.bms.jbdmanager.model.SpeedRangeStats
import com.bms.jbdmanager.model.TripSessionRecord
import com.bms.jbdmanager.model.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
//MARK:测试快照
//LastSnapshotStoreTest 验证 LastSnapshotStore 的正常流程、边界输入和需要长期保持的回归行为。
class LastSnapshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    //MARK:测试快照
    //clearPreviousSnapshot 在每个用例执行前清理或建立隔离环境，防止本地残留数据影响断言。
    fun clearPreviousSnapshot() {
        context.getSharedPreferences("jbd_last_snapshot", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("jbd_mileage_history", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    //MARK:测试快照快照
    //验证新版本快照completely替换previous快照场景的关键输出，防止后续修改破坏既有行为。
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

    @Test
    //MARK:测试历史快照
    //验证里程历史isavailablefromoffline快照场景的关键输出，防止后续修改破坏既有行为。
    fun mileageHistoryIsAvailableFromOfflineSnapshot() {
        val store = LastSnapshotStore(context)
        val session = TripSessionRecord(
            startedAtMillis = 10_000L,
            finishedAtMillis = 20_000L,
            distanceMeters = 12_600.0,
            consumedAh = 4.2,
            consumedWh = 220.0,
            maximumRegeneration = RegenerationPeak(20.0, 1_120.0, 41.5, 15_000L)
        )
        store.save(
            sampleState(55.7, 52, listOf(3276, 3279)).copy(
                trip = TripState(
                    distanceMeters = 0.0,
                    currentRemainingAh = 24.5,
                    speedRangeStats = listOf(
                        SpeedRangeStats(40, 22_400.0, 2_020.0, 8.8, 466.0)
                    )
                ),
                mileageHistory = MileageHistoryState(sessions = listOf(session))
            ),
            nowMillis = 30_000L
        )

        val restored = store.load()!!

        assertEquals(1, restored.mileageHistory.sessions.size)
        assertEquals(12_600.0, restored.mileageHistory.sessions.single().distanceMeters, 0.001)
        assertEquals(12_600.0, restored.trip.distanceMeters, 0.001)
        assertEquals(10_000L, restored.trip.startedAtMillis)
        assertEquals(22_400.0, restored.trip.speedRangeStats.first { it.targetSpeedKmh == 40 }.effectiveDistanceMeters, 0.001)
        assertEquals(24.5, restored.trip.currentRemainingAh!!, 0.001)
        assertEquals(1_120.0, restored.trip.maximumRegeneration!!.powerW, 0.001)
        assertEquals(41.5, restored.mileageHistory.maximumRegeneration!!.speedKmh, 0.001)
    }

    @Test
    //MARK:测试参数快照
    //验证保护paramsareavailablefromoffline快照场景的关键输出，防止后续修改破坏既有行为。
    fun protectionParamsAreAvailableFromOfflineSnapshot() {
        val store = LastSnapshotStore(context)
        val params = JbdProtectionParams(
            fullChargeVoltageV = 3.65,
            cellOvervoltageV = 3.75,
            dischargeOvercurrentA = 120.0,
            chargeHighTempC = 50.0,
            dischargeLowTempC = -20.0
        )
        store.save(
            sampleState(55.7, 52, listOf(3276, 3279)).copy(protectionParams = params),
            nowMillis = 40_000L
        )

        val restored = store.load()!!

        assertEquals(params, restored.protectionParams)
        assertEquals(params, restored.asUiState().protectionParams)
    }

    //MARK:测试样本状态
    //构造包含基本信息、单体、行程和历史的完整测试状态，供报告或快照用例复用。
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
