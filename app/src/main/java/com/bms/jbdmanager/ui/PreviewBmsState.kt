package com.bms.jbdmanager.ui

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.SpeedRangeStats
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.defaultSpeedRangeStats
import com.bms.jbdmanager.update.AppUpdateState

internal fun demoBmsState(
    appUpdate: AppUpdateState = AppUpdateState(
        currentVersionName = "0.5.4",
        currentVersionCode = 34
    )
): BmsUiState {
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
        appUpdate = appUpdate
    )
}
