package com.bms.jbdmanager.model

import com.bms.jbdmanager.update.AppUpdateState
import kotlin.math.roundToInt

/** 电动自行车动能回收会产生充电电流，只有静置且大于 7A 才视为插枪充电。 */
const val SignificantChargeCurrentA = 7.0

/** GPS 静置时可能有微小漂移，低于此视为车速为 0。 */
const val StationarySpeedKmh = 1.0

//MARK:判断充电
//isStationaryCharging 只有电流达到充电阈值且 GPS 速度接近静止时才判定为插电充电，避免把骑行回收误判为充电。
fun isStationaryCharging(currentA: Double, speedKmh: Double): Boolean =
    currentA > SignificantChargeCurrentA && speedKmh < StationarySpeedKmh

/** BMS 的 SOC 在满充时可能显示 99% 或 100%，剩余容量也可能是 50.0Ah / 49.9Ah。 */
const val FullChargeSocPercent = 99
const val FullChargeCapacityRatio = 0.99
const val FullChargeCapacitySlackAh = 0.15

//MARK:判断有效满充
//isEffectivelyFullyCharged 综合 SOC、单体最低电压和充电电流判断可靠满充，避免仅凭 BMS 百分比误判。
fun BmsBasicInfo.isEffectivelyFullyCharged(): Boolean {
    if (stateOfChargePercent >= FullChargeSocPercent) return true
    val fullAh = fullChargeCapacityAh?.takeIf { it > 0.0 }
        ?: nominalCapacityAh.takeIf { it > 0.0 }
        ?: return false
    if (remainingCapacityAh <= 0.0) return false
    return remainingCapacityAh + FullChargeCapacitySlackAh >= fullAh ||
        remainingCapacityAh >= fullAh * FullChargeCapacityRatio
}

//MARK:电池基础信息
//BmsBasicInfo 汇总一次信息的计算或读取结果，调用方无需再从原始字段重复推导。
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
    //MARK:判断充电
    //isCharging 根据当前电流和速度调用统一充电判定，供通知与页面共用相同逻辑。
    fun isCharging(speedKmh: Double): Boolean = isStationaryCharging(currentA, speedKmh)

    val estimatedSohPercent: Double?
        get() = fullChargeCapacityAh
            ?.takeIf { nominalCapacityAh > 0.0 }
            ?.let { (it / nominalCapacityAh * 100.0).coerceIn(0.0, 150.0) }
}

//MARK:单体摘要
//CellSummary 汇总一次单体摘要的计算或读取结果，调用方无需再从原始字段重复推导。
data class CellSummary(
    val millivolts: List<Int>,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val minimumMv: Int? get() = millivolts.minOrNull()
    val maximumMv: Int? get() = millivolts.maxOrNull()
    val deltaMv: Int? get() = minimumMv?.let { min -> maximumMv?.minus(min) }
}

//MARK:保护参数
//JbdProtectionParams 将保护参数相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
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

//MARK:扫描设备
//ScanDevice 将扫描设备相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class ScanDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val looksLikeJbd: Boolean
)

//MARK:已存设备
//SavedDevice 将已保存设备相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class SavedDevice(
    val address: String,
    val name: String,
    val lastSocPercent: Int? = null
)

//MARK:连接阶段
//ConnectionPhase 枚举连接的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
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

//MARK:数据新鲜状态
//DataFreshness 枚举数据的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class DataFreshness {
    Waiting,
    Fresh,
    Stale
}

//MARK:续航测试状态
//RangeTestState 保存续航测试状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
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

//MARK:速度续航
//SpeedRangeStats 汇总一次速度续航的计算或读取结果，调用方无需再从原始字段重复推导。
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
    //MARK:估算剩余续航
    //estimatedRemainingKm 用该速度区间累计的每公里耗电量和当前剩余容量估算可继续行驶距离。
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
    //MARK:判断速度区间
    //accepts 判断当前 GPS 速度是否落入该统计区间的左闭右开边界。
    fun accepts(speedKmh: Double): Boolean = speedKmh >= minimumSpeedKmh && speedKmh < maximumSpeedKmh
}

//MARK:续航
//HistoricalRangeEstimate 汇总一次续航的计算或读取结果，调用方无需再从原始字段重复推导。
data class HistoricalRangeEstimate(
    val remainingKm: Double,
    val sourceLabel: String,
    val confidence: String,
    val sampleDistanceKm: Double,
    val ahPer100Km: Double?
)

//MARK:默认分速续航
//defaultSpeedRangeStats 把速度续航映射为统一名称、格式或默认结构，供存储和界面共同复用。
fun defaultSpeedRangeStats(): List<SpeedRangeStats> =
    listOf(25, 30, 35, 40, 45, 50, 55, 60).map(::SpeedRangeStats)

//MARK:行程跟踪模式
//TripTrackingMode 枚举行程跟踪的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class TripTrackingMode {
    Bms,
    MileageOnly
}

//MARK:回收峰值
//记录一次动能回收峰值对应的电流、功率、车速和发生时间；四项数据来自同一个 BMS 样本。
data class RegenerationPeak(
    val currentA: Double,
    val powerW: Double,
    val speedKmh: Double,
    val recordedAtMillis: Long
)

//MARK:更新回收峰值
//仅接受近期 GPS 证明车辆正在行驶的正电流样本，并按回收功率保留本次行程中的最高记录。
internal fun updatedRegenerationPeak(
    existing: RegenerationPeak?,
    totalVoltageV: Double,
    currentA: Double,
    speedKmh: Double,
    lastLocationAtMillis: Long?,
    nowMillis: Long
): RegenerationPeak? {
    val gpsAgeMillis = lastLocationAtMillis?.let(nowMillis::minus) ?: return existing
    if (
        currentA < MINIMUM_REGENERATION_CURRENT_A ||
        totalVoltageV <= 0.0 ||
        speedKmh < MINIMUM_REGENERATION_SPEED_KMH ||
        gpsAgeMillis !in 0..MAXIMUM_REGENERATION_GPS_AGE_MS
    ) return existing

    val candidate = RegenerationPeak(
        currentA = currentA,
        powerW = totalVoltageV * currentA,
        speedKmh = speedKmh,
        recordedAtMillis = nowMillis
    )
    return if (existing == null || candidate.powerW > existing.powerW) candidate else existing
}

private const val MINIMUM_REGENERATION_CURRENT_A = 0.5
private const val MINIMUM_REGENERATION_SPEED_KMH = 3.0
private const val MAXIMUM_REGENERATION_GPS_AGE_MS = 5_000L

//MARK:计算程倒计时
//resolveMileageCountdownReachedAt 纯 GPS 行程首次达到目标公里数时记录时间；已有到达时间后保持不变以确保只提醒一次。
internal fun resolveMileageCountdownReachedAt(
    existingReachedAtMillis: Long?,
    mileageOnly: Boolean,
    distanceMeters: Double,
    targetKm: Int,
    timestampMillis: Long
): Long? = when {
    !mileageOnly -> existingReachedAtMillis
    existingReachedAtMillis != null -> existingReachedAtMillis
    distanceMeters >= targetKm * 1_000.0 -> timestampMillis
    else -> null
}

//MARK:行程状态
//TripState 保存行程状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
data class TripState(
    val isTracking: Boolean = false,
    val trackingMode: TripTrackingMode = TripTrackingMode.Bms,
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
    val mileageCountdownTargetKm: Int = 30,
    val mileageCountdownReachedAtMillis: Long? = null,
    val mileageCountdownAcknowledged: Boolean = false,
    val maximumRegeneration: RegenerationPeak? = null,
    val rangeTest: RangeTestState = RangeTestState(),
    val speedRangeStats: List<SpeedRangeStats> = defaultSpeedRangeStats()
) {
    val isMileageOnly: Boolean get() = trackingMode == TripTrackingMode.MileageOnly
    val distanceKm: Double get() = distanceMeters / 1_000.0
    val mileageCountdownRemainingKm: Double
        get() = (mileageCountdownTargetKm - distanceKm).coerceAtLeast(0.0)
    val mileageCountdownRemainingPercent: Int
        get() = (
            mileageCountdownRemainingKm / mileageCountdownTargetKm.coerceAtLeast(1) * 100.0
            ).roundToInt().coerceIn(0, 100)
    val mileageCountdownReached: Boolean
        get() = mileageCountdownReachedAtMillis != null
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

    //MARK:历史续航估算
    //historicalRangeEstimate 根据当前速度选择样本最接近且数据量足够的区间，返回历史续航估算和可信度。
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

//MARK:速度状态
//GpsSpeedState 保存GPS速度状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
data class GpsSpeedState(
    val currentKmh: Double = 0.0,
    val average5SecondsKmh: Double = 0.0,
    val maximumKmh: Double = 0.0
)

//MARK:电池界面状态
//BmsUiState 保存状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
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
    val temperatureAlertUsesExternalSurface: Boolean = false,
    val fullScreenTemperatureAlertGranted: Boolean = false,
    val overlayTemperatureAlertGranted: Boolean = false
) {
    val isCharging: Boolean
        get() = basicInfo?.isCharging(trip.currentSpeedKmh) == true
}
