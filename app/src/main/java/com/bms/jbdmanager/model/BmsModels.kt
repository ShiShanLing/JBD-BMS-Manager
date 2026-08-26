package com.bms.jbdmanager.model

import com.bms.jbdmanager.update.AppUpdateState

/** 电动自行车动能回收会产生充电电流，只有静置且大于 7A 才视为插枪充电。 */
const val SignificantChargeCurrentA = 7.0

/** GPS 静置时可能有微小漂移，低于此视为车速为 0。 */
const val StationarySpeedKmh = 1.0

fun isStationaryCharging(currentA: Double, speedKmh: Double): Boolean =
    currentA > SignificantChargeCurrentA && speedKmh < StationarySpeedKmh

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
    fun isCharging(speedKmh: Double): Boolean = isStationaryCharging(currentA, speedKmh)

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

data class JbdProtectionParams(
    val fullChargeVoltageV: Double? = null,
    val cellOvervoltageV: Double? = null,
    val cellOvervoltageReleaseV: Double? = null,
    val cellUndervoltageV: Double? = null,
    val cellUndervoltageReleaseV: Double? = null,
    val packOvervoltageV: Double? = null,
    val packOvervoltageReleaseV: Double? = null,
    val packUndervoltageV: Double? = null,
    val packUndervoltageReleaseV: Double? = null,
    val chargeOvercurrentA: Double? = null,
    val dischargeOvercurrentA: Double? = null,
    val chargeHighTempC: Double? = null,
    val chargeHighTempReleaseC: Double? = null,
    val chargeLowTempC: Double? = null,
    val chargeLowTempReleaseC: Double? = null,
    val dischargeHighTempC: Double? = null,
    val dischargeHighTempReleaseC: Double? = null,
    val dischargeLowTempC: Double? = null,
    val dischargeLowTempReleaseC: Double? = null
)

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

data class HistoricalRangeEstimate(
    val remainingKm: Double,
    val sourceLabel: String,
    val confidence: String,
    val sampleDistanceKm: Double,
    val ahPer100Km: Double?
)

fun defaultSpeedRangeStats(): List<SpeedRangeStats> =
    listOf(25, 30, 35, 40, 45, 50, 55, 60).map(::SpeedRangeStats)

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
    val speedRangeStats: List<SpeedRangeStats> = defaultSpeedRangeStats()
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

    fun historicalRangeEstimate(remainingAh: Double? = currentRemainingAh): HistoricalRangeEstimate? {
        val remaining = remainingAh ?: return null
        val matching = speedRangeStats.firstOrNull { it.accepts(currentSpeedKmh) }
        val matchingKm = matching?.estimatedRemainingKm(remaining)
        if (matching != null && matchingKm != null) {
            return HistoricalRangeEstimate(
                remainingKm = matchingKm,
                sourceLabel = "${matching.targetSpeedKmh}km/h 档",
                confidence = matching.confidence,
                sampleDistanceKm = matching.effectiveDistanceKm,
                ahPer100Km = matching.ahPer100Km
            )
        }
        val usable = speedRangeStats.filter { it.effectiveDistanceKm >= 3.0 && it.consumedAh >= 0.5 }
        if (usable.isEmpty()) return null
        val totalDistance = usable.sumOf { it.effectiveDistanceKm }
        val totalConsumed = usable.sumOf { it.consumedAh }
        if (totalDistance < 3.0 || totalConsumed < 0.5) return null
        return HistoricalRangeEstimate(
            remainingKm = (remaining / (totalConsumed / totalDistance)).coerceAtLeast(0.0),
            sourceLabel = "综合历史",
            confidence = when {
                totalDistance >= 15.0 && totalConsumed >= 2.0 -> "较稳定"
                else -> "初步估算"
            },
            sampleDistanceKm = totalDistance,
            ahPer100Km = totalConsumed / totalDistance * 100.0
        )
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
    val protectionParams: JbdProtectionParams? = null,
    val protectionParamsLoading: Boolean = false,
    val protectionParamsError: String? = null,
    val errorMessage: String? = null,
    val appUpdate: AppUpdateState = AppUpdateState(currentVersionName = "", currentVersionCode = 0),
    val mileageHistory: MileageHistoryState = MileageHistoryState(),
    val capacityHealthRecords: List<CapacityHealthRecord> = emptyList(),
    val automaticCapacityTest: AutomaticCapacityTestState = AutomaticCapacityTestState(),
    val protectionEvents: List<ProtectionEvent> = emptyList(),
    val batteryTrend: BatteryTrendState = BatteryTrendState(),
    val dataManagement: DataManagementState = DataManagementState(),
    val temperatureSafetyAlert: TemperatureSafetyAlert? = null,
    val fullScreenTemperatureAlertGranted: Boolean = false,
    val overlayTemperatureAlertGranted: Boolean = false
) {
    val isCharging: Boolean
        get() = basicInfo?.isCharging(trip.currentSpeedKmh) == true
}
