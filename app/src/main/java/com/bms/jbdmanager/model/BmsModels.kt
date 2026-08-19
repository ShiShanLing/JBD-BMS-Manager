package com.bms.jbdmanager.model

import com.bms.jbdmanager.update.AppUpdateState

data class BmsBasicInfo(
    val totalVoltageV: Double,
    val currentA: Double,
    val remainingCapacityAh: Double,
    val nominalCapacityAh: Double,
    val fullChargeCapacityAh: Double?,
    val stateOfChargePercent: Int,
    val cycleCount: Int,
    val temperaturesC: List<Double>,
    val cellCount: Int,
    val chargeMosEnabled: Boolean,
    val dischargeMosEnabled: Boolean,
    val balancingMask: Long,
    val protectionMask: Int,
    val alarmMask: Int?,
    val softwareVersion: String,
    val productionDate: String?,
    val humidityPercent: Int?,
    val balancingCurrentMa: Int?,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val estimatedSohPercent: Double?
        get() = fullChargeCapacityAh
            ?.takeIf { nominalCapacityAh > 0.0 }
            ?.let { (it / nominalCapacityAh * 100.0).coerceIn(0.0, 150.0) }
}

data class CellSummary(
    val millivolts: List<Int>,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val minimumMv: Int? get() = millivolts.minOrNull()
    val maximumMv: Int? get() = millivolts.maxOrNull()
    val deltaMv: Int? get() = minimumMv?.let { min -> maximumMv?.minus(min) }
}

data class ScanDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val looksLikeJbd: Boolean
)

data class SavedDevice(
    val address: String,
    val name: String,
    val lastSocPercent: Int? = null
)

enum class ConnectionPhase {
    Idle,
    Scanning,
    Connecting,
    Reconnecting,
    Discovering,
    Ready,
    Disconnecting,
    Error
}

enum class DataFreshness {
    Waiting,
    Fresh,
    Stale
}

data class RangeTestState(
    val isActive: Boolean = false,
    val targetSpeedKmh: Int = 40,
    val speedToleranceKmh: Int = 5,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val effectiveDistanceMeters: Double = 0.0,
    val effectiveDurationSeconds: Double = 0.0,
    val consumedAh: Double = 0.0,
    val consumedWh: Double = 0.0,
    val startSocPercent: Int? = null,
    val currentSocPercent: Int? = null,
    val startRemainingAh: Double? = null,
    val currentRemainingAh: Double? = null
) {
    val minimumSpeedKmh: Int get() = (targetSpeedKmh - speedToleranceKmh).coerceAtLeast(0)
    val maximumSpeedKmh: Int get() = targetSpeedKmh + speedToleranceKmh
    val effectiveDistanceKm: Double get() = effectiveDistanceMeters / 1_000.0
    val averageSpeedKmh: Double?
        get() = effectiveDurationSeconds.takeIf { it > 0.0 }
            ?.let { effectiveDistanceKm / (it / 3_600.0) }
    val ahPer100Km: Double?
        get() = effectiveDistanceKm.takeIf { it >= 0.5 && consumedAh >= 0.05 }
            ?.let { consumedAh / it * 100.0 }
    val whPerKm: Double?
        get() = effectiveDistanceKm.takeIf { it >= 0.5 && consumedWh >= 2.0 }
            ?.let { consumedWh / it }
    val estimatedRemainingKm: Double?
        get() {
            val remaining = currentRemainingAh ?: return null
            if (effectiveDistanceKm < 1.0 || consumedAh < 0.1) return null
            return (remaining / (consumedAh / effectiveDistanceKm)).coerceAtLeast(0.0)
        }
    val confidence: String
        get() = when {
            effectiveDistanceKm >= 15.0 && consumedAh >= 2.0 -> "较稳定"
            effectiveDistanceKm >= 3.0 && consumedAh >= 0.5 -> "初步估算"
            else -> "采集中"
        }
}

data class SpeedRangeStats(
    val targetSpeedKmh: Int,
    val effectiveDistanceMeters: Double = 0.0,
    val effectiveDurationSeconds: Double = 0.0,
    val consumedAh: Double = 0.0,
    val consumedWh: Double = 0.0
) {
    val minimumSpeedKmh: Double get() = targetSpeedKmh - 2.5
    val maximumSpeedKmh: Double get() = targetSpeedKmh + 2.5
    val effectiveDistanceKm: Double get() = effectiveDistanceMeters / 1_000.0
    val averageSpeedKmh: Double?
        get() = effectiveDurationSeconds.takeIf { it > 0.0 }
            ?.let { effectiveDistanceKm / (it / 3_600.0) }
    val ahPer100Km: Double?
        get() = effectiveDistanceKm.takeIf { it >= 0.5 && consumedAh >= 0.05 }
            ?.let { consumedAh / it * 100.0 }
    val whPerKm: Double?
        get() = effectiveDistanceKm.takeIf { it >= 0.5 && consumedWh >= 2.0 }
            ?.let { consumedWh / it }
    fun estimatedRemainingKm(remainingAh: Double?): Double? {
        if (remainingAh == null || effectiveDistanceKm < 3.0 || consumedAh < 0.5) return null
        return (remainingAh / (consumedAh / effectiveDistanceKm)).coerceAtLeast(0.0)
    }
    val confidence: String
        get() = when {
            effectiveDistanceKm >= 15.0 && consumedAh >= 2.0 -> "较稳定"
            effectiveDistanceKm >= 3.0 && consumedAh >= 0.5 -> "初步估算"
            else -> "采集中"
        }
    fun accepts(speedKmh: Double): Boolean = speedKmh >= minimumSpeedKmh && speedKmh < maximumSpeedKmh
}

fun defaultSpeedRangeStats(): List<SpeedRangeStats> =
    listOf(25, 30, 35, 40, 45, 50, 55, 60).map(::SpeedRangeStats)

enum class BrakeTestPhase { Idle, Armed, Ready, Braking, Complete, Failed }

data class BrakeTestState(
    val targetSpeedKmh: Int = 40,
    val phase: BrakeTestPhase = BrakeTestPhase.Idle,
    val currentSpeedKmh: Double = 0.0,
    val startSpeedKmh: Double? = null,
    val brakingDistanceMeters: Double = 0.0,
    val brakingDurationSeconds: Double = 0.0,
    val averageDecelerationMps2: Double? = null,
    val sampleRateHz: Double = 0.0,
    val speedAccuracyKmh: Double? = null,
    val message: String = "选择目标速度后开始测试",
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val previousSampleAtMillis: Long? = null,
    val previousSpeedKmh: Double? = null,
    val recentSampleTimesMillis: List<Long> = emptyList(),
    val stoppedSampleCount: Int = 0
) {
    val isRunning: Boolean
        get() = phase == BrakeTestPhase.Armed || phase == BrakeTestPhase.Ready || phase == BrakeTestPhase.Braking
    val confidence: String
        get() = when {
            phase != BrakeTestPhase.Complete -> "等待完成"
            sampleRateHz >= 5.0 && (speedAccuracyKmh ?: Double.MAX_VALUE) <= 2.0 -> "较高"
            sampleRateHz >= 2.0 && (speedAccuracyKmh ?: Double.MAX_VALUE) <= 4.0 -> "中等"
            else -> "仅供参考"
        }
}

data class TripState(
    val isTracking: Boolean = false,
    val startedAtMillis: Long? = null,
    val distanceMeters: Double = 0.0,
    val startSocPercent: Int? = null,
    val currentSocPercent: Int? = null,
    val startRemainingAh: Double? = null,
    val currentRemainingAh: Double? = null,
    val integratedConsumedAh: Double = 0.0,
    val integratedConsumedWh: Double = 0.0,
    val currentA: Double = 0.0,
    val currentSpeedKmh: Double = 0.0,
    val locationAccuracyMeters: Float? = null,
    val validLocationPoints: Int = 0,
    val lastLocationAtMillis: Long? = null,
    val gpsMessage: String = "等待开始行程",
    val rangeTest: RangeTestState = RangeTestState(),
    val speedRangeStats: List<SpeedRangeStats> = defaultSpeedRangeStats(),
    val brakeTest: BrakeTestState = BrakeTestState()
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
    val socDropPercent: Int?
        get() = startSocPercent?.let { start -> currentSocPercent?.let { (start - it).coerceAtLeast(0) } }
    val bmsConsumedAh: Double?
        get() = startRemainingAh?.let { start -> currentRemainingAh?.let { (start - it).coerceAtLeast(0.0) } }
    val consumedAh: Double
        get() = maxOf(integratedConsumedAh, bmsConsumedAh ?: 0.0)
    val ahPer100Km: Double?
        get() = distanceKm.takeIf { it >= 0.5 && consumedAh >= 0.05 }?.let { consumedAh / it * 100.0 }
    val whPerKm: Double?
        get() = distanceKm.takeIf { it >= 0.5 && integratedConsumedWh >= 2.0 }
            ?.let { integratedConsumedWh / it }
    val estimatedRemainingKm: Double?
        get() {
            val remaining = currentRemainingAh ?: return null
            val distance = distanceKm
            val used = consumedAh
            if (distance < 1.0 || used < 0.1) return null
            return (remaining / (used / distance)).coerceAtLeast(0.0)
        }
    val estimateConfidence: String
        get() = when {
            distanceKm >= 15.0 && consumedAh >= 2.0 -> "较稳定"
            distanceKm >= 3.0 && consumedAh >= 0.5 -> "初步估算"
            else -> "采集中"
    }
}

data class GpsSpeedState(
    val currentKmh: Double = 0.0,
    val average5SecondsKmh: Double = 0.0,
    val maximumKmh: Double = 0.0
)

data class BmsUiState(
    val bluetoothSupported: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val permissionsGranted: Boolean = false,
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val isScanning: Boolean = false,
    val devices: List<ScanDevice> = emptyList(),
    val savedDevices: List<SavedDevice> = emptyList(),
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val connectedAddress: String? = null,
    val connectedName: String? = null,
    val reconnectAttempt: Int = 0,
    val reconnectInSeconds: Int? = null,
    val modelName: String? = null,
    val protocolProfile: String = "等待识别",
    val detectedProtocol: String? = null,
    val bleChannelDetails: String? = null,
    val chipType: String? = null,
    val basicInfo: BmsBasicInfo? = null,
    val cells: CellSummary? = null,
    val dataFreshness: DataFreshness = DataFreshness.Waiting,
    val communicationReadyAtMillis: Long? = null,
    val lastValidDataAtMillis: Long? = null,
    val lastDataAgeSeconds: Int? = null,
    val authenticationRequired: Boolean = false,
    val authenticationMessage: String? = null,
    val locationPermissionGranted: Boolean = false,
    val trip: TripState = TripState(),
    val gpsSpeed: GpsSpeedState = GpsSpeedState(),
    val lastSnapshot: LastBmsSnapshot? = null,
    val errorMessage: String? = null,
    val appUpdate: AppUpdateState = AppUpdateState(currentVersionName = "", currentVersionCode = 0)
)
