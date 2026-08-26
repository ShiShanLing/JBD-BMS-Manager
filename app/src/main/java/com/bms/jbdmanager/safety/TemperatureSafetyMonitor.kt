package com.bms.jbdmanager.safety

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.TemperatureAlertLevel
import com.bms.jbdmanager.model.TemperatureMonitorUpdate
import com.bms.jbdmanager.model.TemperatureSafetyAlert
import kotlin.math.min

internal class TemperatureSafetyMonitor {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var activeLevel: TemperatureAlertLevel? = null
    private var warningMatches = 0
    private var criticalMatches = 0
    private var recoveryMatches = 0

    fun update(
        info: BmsBasicInfo,
        protectionParams: JbdProtectionParams?,
        nowMillis: Long = System.currentTimeMillis()
    ): TemperatureMonitorUpdate {
        val maximumTemperatureC = info.temperaturesC
            .filter { it in MIN_VALID_TEMPERATURE_C..MAX_VALID_TEMPERATURE_C }
            .maxOrNull() ?: return TemperatureMonitorUpdate()
        samples.addLast(nowMillis to maximumTemperatureC)
        while (samples.isNotEmpty() && nowMillis - samples.first().first > SAMPLE_WINDOW_MS) {
            samples.removeFirst()
        }

        val configuredLimit = when {
            info.currentA > 7.0 -> protectionParams?.chargeHighTempC
            info.currentA < -0.05 -> protectionParams?.dischargeHighTempC
            else -> listOfNotNull(
                protectionParams?.chargeHighTempC,
                protectionParams?.dischargeHighTempC
            ).minOrNull()
        }
        val criticalThresholdC = min(configuredLimit ?: DEFAULT_CRITICAL_C, MAX_CRITICAL_C)
        val warningThresholdC = (criticalThresholdC - WARNING_MARGIN_C).coerceAtLeast(MIN_WARNING_C)
        val riseRate = riseRateCPerMinute(nowMillis, maximumTemperatureC)
        val bmsHighTemperatureProtection = info.protectionMask and ((1 shl 4) or (1 shl 6)) != 0
        val rapidCritical = maximumTemperatureC >= RAPID_CRITICAL_MIN_C &&
            riseRate != null && riseRate >= RAPID_CRITICAL_RATE_C_PER_MINUTE
        val rapidWarning = maximumTemperatureC >= RAPID_WARNING_MIN_C &&
            riseRate != null && riseRate >= RAPID_WARNING_RATE_C_PER_MINUTE
        val critical = bmsHighTemperatureProtection || maximumTemperatureC >= criticalThresholdC || rapidCritical
        val warning = maximumTemperatureC >= warningThresholdC || rapidWarning

        criticalMatches = if (critical) criticalMatches + 1 else 0
        warningMatches = if (warning) warningMatches + 1 else 0
        val requestedLevel = when {
            bmsHighTemperatureProtection || criticalMatches >= CRITICAL_CONFIRM_SAMPLES -> TemperatureAlertLevel.Critical
            warningMatches >= WARNING_CONFIRM_SAMPLES -> TemperatureAlertLevel.Warning
            else -> null
        }

        if (requestedLevel != null && requestedLevel != activeLevel) {
            if (activeLevel == TemperatureAlertLevel.Critical && requestedLevel == TemperatureAlertLevel.Warning) {
                return TemperatureMonitorUpdate()
            }
            activeLevel = requestedLevel
            recoveryMatches = 0
            val cause = when {
                bmsHighTemperatureProtection -> "BMS 已触发高温保护"
                rapidCritical || rapidWarning -> "温度正在快速上升"
                requestedLevel == TemperatureAlertLevel.Critical -> "温度已达到危险阈值"
                else -> "温度已接近 BMS 高温保护阈值"
            }
            val action = if (requestedLevel == TemperatureAlertLevel.Critical) {
                "请立即停止骑行或充电，远离可燃物；如有异味、冒烟或异常发热，请远离电池并联系消防救援。"
            } else {
                "请降低负载并尽快停车检查；如果温度继续上升，请停止使用电池。"
            }
            return TemperatureMonitorUpdate(
                alert = TemperatureSafetyAlert(
                    id = nowMillis,
                    level = requestedLevel,
                    title = if (requestedLevel == TemperatureAlertLevel.Critical) "电池高温危险" else "电池温度警告",
                    message = "$cause，最高温度 ${formatTemperature(maximumTemperatureC)}℃。$action",
                    maximumTemperatureC = maximumTemperatureC,
                    warningThresholdC = warningThresholdC,
                    criticalThresholdC = criticalThresholdC,
                    riseRateCPerMinute = riseRate,
                    triggeredAtMillis = nowMillis
                )
            )
        }

        val recovered = activeLevel != null &&
            !bmsHighTemperatureProtection &&
            maximumTemperatureC <= warningThresholdC - RECOVERY_MARGIN_C
        recoveryMatches = if (recovered) recoveryMatches + 1 else 0
        if (recoveryMatches >= RECOVERY_CONFIRM_SAMPLES) {
            reset(clearSamples = false)
            return TemperatureMonitorUpdate(recovered = true)
        }
        return TemperatureMonitorUpdate()
    }

    fun reset(clearSamples: Boolean = true) {
        activeLevel = null
        warningMatches = 0
        criticalMatches = 0
        recoveryMatches = 0
        if (clearSamples) samples.clear()
    }

    private fun riseRateCPerMinute(nowMillis: Long, currentTemperatureC: Double): Double? {
        val oldest = samples.firstOrNull() ?: return null
        val elapsedMillis = nowMillis - oldest.first
        if (elapsedMillis < MIN_RISE_SAMPLE_SPAN_MS) return null
        return (currentTemperatureC - oldest.second) * 60_000.0 / elapsedMillis
    }

    private fun formatTemperature(value: Double): String = "%.1f".format(value)

    private companion object {
        const val MIN_VALID_TEMPERATURE_C = -40.0
        const val MAX_VALID_TEMPERATURE_C = 120.0
        const val DEFAULT_CRITICAL_C = 60.0
        const val MAX_CRITICAL_C = 60.0
        const val MIN_WARNING_C = 40.0
        const val WARNING_MARGIN_C = 5.0
        const val RECOVERY_MARGIN_C = 3.0
        const val RAPID_WARNING_MIN_C = 40.0
        const val RAPID_CRITICAL_MIN_C = 45.0
        const val RAPID_WARNING_RATE_C_PER_MINUTE = 8.0
        const val RAPID_CRITICAL_RATE_C_PER_MINUTE = 12.0
        const val SAMPLE_WINDOW_MS = 60_000L
        const val MIN_RISE_SAMPLE_SPAN_MS = 15_000L
        const val WARNING_CONFIRM_SAMPLES = 3
        const val CRITICAL_CONFIRM_SAMPLES = 2
        const val RECOVERY_CONFIRM_SAMPLES = 5
    }
}
