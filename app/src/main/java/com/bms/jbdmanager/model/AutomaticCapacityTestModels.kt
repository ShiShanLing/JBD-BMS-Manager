package com.bms.jbdmanager.model

import kotlin.math.max

enum class AutomaticCapacityTestPhase { Idle, Running, Completed }

data class AutomaticCapacityTestState(
    val phase: AutomaticCapacityTestPhase = AutomaticCapacityTestPhase.Idle,
    val autoStartSuppressed: Boolean = false,
    val startedAutomatically: Boolean = false,
    val deviceAddress: String? = null,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val startSocPercent: Int? = null,
    val currentSocPercent: Int? = null,
    val startRemainingAh: Double? = null,
    val currentRemainingAh: Double? = null,
    val ratedCapacityAh: Double? = null,
    val dischargedAh: Double = 0.0,
    val dischargedWh: Double = 0.0,
    val missingDischargeAh: Double = 0.0,
    val chargingInterruptionAh: Double = 0.0,
    val validDurationSeconds: Double = 0.0,
    val sampleCount: Int = 0,
    val temperatureSumC: Double = 0.0,
    val temperatureSampleCount: Int = 0,
    val lowVoltageReached: Boolean = false,
    val finishReason: String? = null,
    val lastSampleAtMillis: Long? = null,
    val lastCurrentA: Double? = null,
    val lastVoltageV: Double? = null,
    val lastRemainingAh: Double? = null
) {
    /** 直接积分值加上断线期间由 BMS 剩余容量差补回的放电量。 */
    val measuredDischargeAh: Double get() = dischargedAh + missingDischargeAh

    val coveragePercent: Double
        get() {
            val total = dischargedAh + missingDischargeAh
            return if (total <= 0.001) 100.0 else (dischargedAh / total * 100.0).coerceIn(0.0, 100.0)
        }

    val averageTemperatureC: Double?
        get() = temperatureSampleCount.takeIf { it > 0 }?.let { temperatureSumC / it }

    val isQualifiedForHealth: Boolean
        get() {
            val rated = ratedCapacityAh ?: return false
            val startOk = (startSocPercent ?: 0) >= 98
            val endOk = lowVoltageReached || (currentSocPercent ?: 100) <= 5
            val chargeOk = chargingInterruptionAh <= rated * 0.02
            return startOk && endOk && coveragePercent >= 90.0 && chargeOk && measuredDischargeAh >= rated * 0.5
        }

    val resultExplanation: String
        get() = when {
            isQualifiedForHealth -> "测试覆盖完整，可计入健康诊断"
            (startSocPercent ?: 0) < 98 -> "起始电量不足 98%，结果仅供参考"
            !lowVoltageReached && (currentSocPercent ?: 100) > 5 -> "尚未到低电量或低压保护，结果仅供参考"
            coveragePercent < 90.0 -> "断线造成的数据缺口较多，结果仅供参考"
            chargingInterruptionAh > (ratedCapacityAh ?: Double.MAX_VALUE) * 0.02 -> "测试途中充电较多，结果仅供参考"
            else -> "有效放电量不足，结果仅供参考"
        }
}

fun shouldStartAutomaticCapacityTest(info: BmsBasicInfo): Boolean {
    val rated = info.nominalCapacityAh
    return rated > 0.0 && info.stateOfChargePercent >= 98 && info.remainingCapacityAh >= rated * 0.95
}

fun startCapacityTest(
    info: BmsBasicInfo,
    nowMillis: Long,
    automatically: Boolean,
    deviceAddress: String? = null
): AutomaticCapacityTestState {
    val averageTemperature = info.temperaturesC.takeIf { it.isNotEmpty() }?.average()
    return AutomaticCapacityTestState(
        phase = AutomaticCapacityTestPhase.Running,
        autoStartSuppressed = false,
        startedAutomatically = automatically,
        deviceAddress = deviceAddress,
        startedAtMillis = nowMillis,
        startSocPercent = info.stateOfChargePercent,
        currentSocPercent = info.stateOfChargePercent,
        startRemainingAh = info.remainingCapacityAh,
        currentRemainingAh = info.remainingCapacityAh,
        ratedCapacityAh = info.nominalCapacityAh.takeIf { it > 0.0 },
        temperatureSumC = averageTemperature ?: 0.0,
        temperatureSampleCount = if (averageTemperature != null) 1 else 0,
        lastSampleAtMillis = nowMillis,
        lastCurrentA = info.currentA,
        lastVoltageV = info.totalVoltageV,
        lastRemainingAh = info.remainingCapacityAh
    )
}

fun updateCapacityTest(
    state: AutomaticCapacityTestState,
    info: BmsBasicInfo,
    speedKmh: Double,
    nowMillis: Long
): AutomaticCapacityTestState {
    if (state.phase != AutomaticCapacityTestPhase.Running) return state
    val previousAt = state.lastSampleAtMillis
    val gapMillis = previousAt?.let { nowMillis - it }
    var dischargedAh = state.dischargedAh
    var dischargedWh = state.dischargedWh
    var missingAh = state.missingDischargeAh
    var chargedAh = state.chargingInterruptionAh
    var validSeconds = state.validDurationSeconds
    var samples = state.sampleCount

    if (gapMillis != null && gapMillis in 250L..15_000L) {
        val hours = gapMillis / 3_600_000.0
        val averageCurrent = ((state.lastCurrentA ?: info.currentA) + info.currentA) / 2.0
        val averageVoltage = ((state.lastVoltageV ?: info.totalVoltageV) + info.totalVoltageV) / 2.0
        val dischargeCurrent = max(-averageCurrent, 0.0)
        dischargedAh += dischargeCurrent * hours
        dischargedWh += dischargeCurrent * averageVoltage * hours
        if (info.isCharging(speedKmh)) chargedAh += max(averageCurrent, 0.0) * hours
        validSeconds += gapMillis / 1_000.0
        samples += 1
    } else if (gapMillis != null && gapMillis > 15_000L) {
        missingAh += max((state.lastRemainingAh ?: info.remainingCapacityAh) - info.remainingCapacityAh, 0.0)
    }

    val lowVoltage = state.lowVoltageReached || info.stateOfChargePercent <= 3 ||
        info.protectionMask and ((1 shl 1) or (1 shl 3)) != 0
    val temperature = info.temperaturesC.takeIf { it.isNotEmpty() }?.average()
    val updated = state.copy(
        currentSocPercent = info.stateOfChargePercent,
        currentRemainingAh = info.remainingCapacityAh,
        dischargedAh = dischargedAh,
        dischargedWh = dischargedWh,
        missingDischargeAh = missingAh,
        chargingInterruptionAh = chargedAh,
        validDurationSeconds = validSeconds,
        sampleCount = samples,
        temperatureSumC = state.temperatureSumC + (temperature ?: 0.0),
        temperatureSampleCount = state.temperatureSampleCount + if (temperature != null) 1 else 0,
        lowVoltageReached = lowVoltage,
        lastSampleAtMillis = nowMillis,
        lastCurrentA = info.currentA,
        lastVoltageV = info.totalVoltageV,
        lastRemainingAh = info.remainingCapacityAh
    )
    return if (lowVoltage) finishCapacityTest(updated, nowMillis, "已到达低电量或低压保护") else updated
}

fun finishCapacityTest(
    state: AutomaticCapacityTestState,
    nowMillis: Long,
    reason: String = "手动结束"
): AutomaticCapacityTestState = state.copy(
    phase = AutomaticCapacityTestPhase.Completed,
    finishedAtMillis = nowMillis,
    finishReason = reason
)
