package com.bms.jbdmanager.ui

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.BatteryTrendState
import com.bms.jbdmanager.model.FullChargeDeltaSample
import com.bms.jbdmanager.model.FullChargeFingerprint
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.SpeedRangeStats
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.defaultSpeedRangeStats
import com.bms.jbdmanager.model.LastBmsSnapshot
import com.bms.jbdmanager.model.MileageHistoryState
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.ProtectionEventSeverity
import com.bms.jbdmanager.model.TripSessionRecord
import com.bms.jbdmanager.update.AppUpdateState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal enum class DemoPreviewScenario {
    Charging,
    Riding,
    Regen,
    Idle;

    fun next(): DemoPreviewScenario = entries[(ordinal + 1) % entries.size]
}

internal fun demoBmsState(
    appUpdate: AppUpdateState = AppUpdateState(
        currentVersionName = "0.5.6",
        currentVersionCode = 36
    ),
    scenario: DemoPreviewScenario = DemoPreviewScenario.Charging
): BmsUiState = applyDemoScenario(baseDemoBmsState(appUpdate), scenario)

internal fun demoLastSnapshot(state: BmsUiState = demoBmsState()): LastBmsSnapshot {
    val info = requireNotNull(state.basicInfo)
    return LastBmsSnapshot(
        savedAtMillis = info.updatedAtMillis,
        deviceAddress = state.connectedAddress,
        deviceName = state.connectedName,
        modelName = state.modelName,
        chipType = state.chipType,
        protocolProfile = state.protocolProfile,
        detectedProtocol = state.detectedProtocol,
        basicInfo = info,
        cells = state.cells,
        protectionParams = state.protectionParams,
        gpsSpeed = state.gpsSpeed,
        trip = state.trip.copy(isTracking = false),
        mileageHistory = state.mileageHistory
    )
}

private fun baseDemoBmsState(appUpdate: AppUpdateState): BmsUiState {
    val now = System.currentTimeMillis()
    return BmsUiState(
        permissionsGranted = true,
        locationPermissionGranted = true,
        phase = ConnectionPhase.Ready,
        connectedAddress = "AA:BB:CC:12:34:56",
        connectedName = "JBD-BMS（测试数据）",
        modelName = "JBD-SP16S100A",
        protocolProfile = "JBD BLE 标准通道",
        detectedProtocol = "JBD DD/77（标准状态帧）",
        bleChannelDetails = "FFE0 服务 · FFE1 通知/写入特征",
        chipType = "JBD 只读演示",
        basicInfo = BmsBasicInfo(
            totalVoltageV = 52.84,
            currentA = 8.36,
            remainingCapacityAh = 78.40,
            nominalCapacityAh = 100.00,
            fullChargeCapacityAh = 96.00,
            stateOfChargePercent = 78,
            cycleCount = 126,
            temperaturesC = listOf(28.4, 46.2, 51.8),
            cellCount = 16,
            chargeMosEnabled = true,
            dischargeMosEnabled = true,
            balancingMask = 1L shl 4,
            protectionMask = 0,
            alarmMask = 0,
            softwareVersion = "V12.3",
            productionDate = "2025-06-18",
            humidityPercent = 42,
            balancingCurrentMa = 68,
            updatedAtMillis = now
        ),
        protectionParams = JbdProtectionParams(
            fullChargeVoltageV = 3.65,
            cellOvervoltageV = 3.75,
            cellOvervoltageReleaseV = 3.55,
            cellUndervoltageV = 2.50,
            cellUndervoltageReleaseV = 2.80,
            packOvervoltageV = 60.00,
            packOvervoltageReleaseV = 56.80,
            packUndervoltageV = 40.00,
            packUndervoltageReleaseV = 44.80,
            chargeOvercurrentA = 50.00,
            dischargeOvercurrentA = 120.00,
            chargeHighTempC = 50.0,
            chargeHighTempReleaseC = 45.0,
            chargeLowTempC = 0.0,
            chargeLowTempReleaseC = 5.0,
            dischargeHighTempC = 65.0,
            dischargeHighTempReleaseC = 55.0,
            dischargeLowTempC = -20.0,
            dischargeLowTempReleaseC = -10.0
        ),
        cells = CellSummary(
            millivolts = listOf(
                3302, 3305, 3298, 3301, 3307, 3304, 3299, 3303,
                3306, 3300, 3302, 3305, 3297, 3301, 3304, 3301
            ),
            updatedAtMillis = now
        ),
        dataFreshness = DataFreshness.Fresh,
        communicationReadyAtMillis = now - 12_000,
        lastValidDataAtMillis = now,
        lastDataAgeSeconds = 0,
        gpsSpeed = GpsSpeedState(
            currentKmh = 0.0,
            average5SecondsKmh = 0.0,
            maximumKmh = 48.3
        ),
        trip = TripState(
            isTracking = true,
            startedAtMillis = now - 2_400_000,
            distanceMeters = 18_600.0,
            startSocPercent = 92,
            currentSocPercent = 78,
            startRemainingAh = 90.0,
            currentRemainingAh = 78.40,
            integratedConsumedAh = 11.4,
            integratedConsumedWh = 602.0,
            currentA = 8.36,
            currentSpeedKmh = 0.0,
            locationAccuracyMeters = 3.4f,
            validLocationPoints = 860,
            lastLocationAtMillis = now,
            gpsMessage = "GPS 行程记录中",
            speedRangeStats = defaultSpeedRangeStats().map { stats ->
                when (stats.targetSpeedKmh) {
                    35 -> SpeedRangeStats(35, 8_200.0, 840.0, 2.6, 138.0)
                    40 -> SpeedRangeStats(40, 22_400.0, 2_020.0, 8.8, 466.0)
                    45 -> SpeedRangeStats(45, 6_100.0, 490.0, 2.9, 154.0)
                    else -> stats
                }
            }
        ),
        mileageHistory = demoMileageHistory(),
        protectionEvents = listOf(
            ProtectionEvent(
                id = now - 86_400_000,
                protectionBit = 3,
                title = "总压过低",
                startedAtMillis = now - 86_460_000,
                resolvedAtMillis = now - 86_400_000,
                severity = ProtectionEventSeverity.Expected,
                summary = "电量接近耗尽时触发，按正常低电量截止记录",
                stateOfChargePercent = 3,
                totalVoltageV = 42.6,
                currentA = -16.8,
                minimumCellMv = 2490,
                maximumCellMv = 2540,
                cellDeltaMv = 50,
                maximumTemperatureC = 38.2,
                deviceAddress = "AA:BB:CC:12:34:56",
                deviceName = "JBD-BMS（测试数据）"
            )
        ),
        capacityHealthRecords = listOf(
            CapacityHealthRecord(
                id = now - 120L * 24 * 60 * 60 * 1_000,
                recordedAtMillis = now - 120L * 24 * 60 * 60 * 1_000,
                measuredDischargeAh = 96.4,
                ratedCapacityAh = 100.0,
                measuredDischargeWh = 5_040.0,
                cycleCount = 42,
                averageTemperatureC = 31.5,
                note = "第一次完整容量测试"
            ),
            CapacityHealthRecord(
                id = now - 10L * 24 * 60 * 60 * 1_000,
                recordedAtMillis = now - 10L * 24 * 60 * 60 * 1_000,
                measuredDischargeAh = 94.2,
                ratedCapacityAh = 100.0,
                measuredDischargeWh = 4_910.0,
                cycleCount = 118,
                averageTemperatureC = 33.0,
                note = "近期复测"
            )
        ),
        batteryTrend = BatteryTrendState(
            points = (0 until 36).map { index ->
                val progress = index / 35.0
                BatteryTrendPoint(
                    timestampMillis = now - (35 - index) * 10 * 60_000L,
                    totalVoltageV = 54.2 - progress * 1.36 + kotlin.math.sin(index / 3.0) * 0.08,
                    currentA = if (index % 6 < 4) -18.0 - index % 4 else 0.2,
                    socPercent = 92.0 - progress * 14.0,
                    maximumTemperatureC = 31.0 + kotlin.math.sin(index / 5.0) * 4.0,
                    cellDeltaMv = 4.0 + index % 5,
                    minimumCellMv = 3385.0 - progress * 84.0
                )
            },
            fullChargeFingerprints = listOf(
                FullChargeFingerprint(
                    capturedAtMillis = now - 180L * 24 * 60 * 60 * 1_000,
                    totalVoltageV = 55.92,
                    socPercent = 100,
                    maximumTemperatureC = 31.2,
                    cellVoltagesMv = listOf(3494, 3497, 3492, 3495, 3496, 3493, 3498, 3494, 3495, 3497, 3492, 3495, 3496, 3494, 3497, 3495)
                ),
                FullChargeFingerprint(
                    capturedAtMillis = now,
                    totalVoltageV = 55.78,
                    socPercent = 100,
                    maximumTemperatureC = 32.4,
                    cellVoltagesMv = listOf(3487, 3491, 3488, 3489, 3492, 3489, 3494, 3490, 3491, 3492, 3452, 3490, 3491, 3489, 3492, 3490)
                )
            ),
            fullChargeDeltas = List(24) { index ->
                val monthsAgo = 23L - index
                FullChargeDeltaSample(
                    capturedAtMillis = now - monthsAgo * 30L * 24 * 60 * 60 * 1_000,
                    cellDeltaMv = 40 - index * 22 / 23,
                    totalVoltageV = 55.96 - index * 0.008,
                    currentA = if (index % 4 == 0) 0.3 else 0.1,
                    socPercent = if (index % 5 == 0) 99 else 100,
                    maximumTemperatureC = 30.0 + (index % 4),
                    remainingCapacityAh = if (index % 3 == 0) 49.8 else 50.0
                )
            }
        ),
        appUpdate = appUpdate
    )
}

private fun applyDemoScenario(state: BmsUiState, scenario: DemoPreviewScenario): BmsUiState {
    val info = state.basicInfo ?: return state
    return when (scenario) {
        DemoPreviewScenario.Charging -> state
        DemoPreviewScenario.Riding -> {
            val currentA = -18.4
            val speedKmh = 41.2
            state.copy(
                basicInfo = info.copy(currentA = currentA, stateOfChargePercent = 64),
                gpsSpeed = GpsSpeedState(
                    currentKmh = speedKmh,
                    average5SecondsKmh = 39.5,
                    maximumKmh = 48.3
                ),
                trip = state.trip.copy(
                    currentA = currentA,
                    currentSpeedKmh = speedKmh,
                    currentSocPercent = 64,
                    currentRemainingAh = 64.0,
                    gpsMessage = "GPS 行程记录中"
                )
            )
        }
        DemoPreviewScenario.Regen -> {
            val currentA = 4.2
            val speedKmh = 28.0
            state.copy(
                basicInfo = info.copy(currentA = currentA),
                gpsSpeed = GpsSpeedState(
                    currentKmh = speedKmh,
                    average5SecondsKmh = 26.5,
                    maximumKmh = 48.3
                ),
                trip = state.trip.copy(
                    currentA = currentA,
                    currentSpeedKmh = speedKmh,
                    gpsMessage = "GPS 行程记录中"
                )
            )
        }
        DemoPreviewScenario.Idle -> {
            val currentA = 0.02
            state.copy(
                basicInfo = info.copy(currentA = currentA),
                gpsSpeed = GpsSpeedState(
                    currentKmh = 0.0,
                    average5SecondsKmh = 0.0,
                    maximumKmh = 48.3
                ),
                trip = state.trip.copy(
                    currentA = currentA,
                    currentSpeedKmh = 0.0,
                    gpsMessage = "GPS 已就绪"
                )
            )
        }
    }
}

private fun demoMileageHistory(): MileageHistoryState {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val monthStart = today.withDayOfMonth(1)
    val edgeTestDates = listOf(weekStart, monthStart, today).distinct()
    val edgeSessions = edgeTestDates.map { date ->
        val start = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        TripSessionRecord(
            startedAtMillis = start,
            finishedAtMillis = start + 3_600_000,
            distanceMeters = 299_000.0,
            consumedAh = 12.0,
            consumedWh = 620.0
        )
    }
    val yearlySessions = listOf(2024, 2025).map { year ->
        val date = LocalDate.of(year, 6, 15)
        val start = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        TripSessionRecord(
            startedAtMillis = start,
            finishedAtMillis = start + 3_600_000,
            distanceMeters = (year - 2020) * 1_000.0,
            consumedAh = 2.0,
            consumedWh = 100.0
        )
    }
    val sessions = (0 until 18).mapNotNull { offset ->
        if (offset % 2 != 0) return@mapNotNull null
        val date = today.minusDays(offset.toLong())
        if (date in edgeTestDates) return@mapNotNull null
        val start = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        TripSessionRecord(
            startedAtMillis = start,
            finishedAtMillis = start + 3_600_000,
            distanceMeters = (4.0 + offset * 0.8) * 1_000.0,
            consumedAh = 1.1 + offset * 0.08,
            consumedWh = 55.0 + offset * 3.0
        )
    }
    return MileageHistoryState(
        sessions = edgeSessions + yearlySessions + sessions,
        activeTripDistanceMeters = 2_800.0,
        activeTripStartedAtMillis = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    )
}
