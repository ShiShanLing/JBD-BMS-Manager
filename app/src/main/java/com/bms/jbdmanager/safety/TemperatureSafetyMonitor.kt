package com.bms.jbdmanager.safety

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.TemperatureAlertLevel
import com.bms.jbdmanager.model.TemperatureMonitorUpdate
import com.bms.jbdmanager.model.TemperatureSafetyAlert
import kotlin.math.min

//MARK:温度安全监控
//TemperatureSafetyMonitor 维护安全采样窗口、阈值判断和恢复条件，用于处理温度安全。
internal class TemperatureSafetyMonitor {
    //MARK:温升窗口
    //RiseWindow 定义一个升温检测窗口的总时长和允许参与计算的最小实际样本跨度。
    private data class RiseWindow(val seconds: Int, val minimumSpanSeconds: Int)
    //MARK:温升观测值
    //RiseObservation 保存某个时间窗计算出的每分钟升温速率，供警报选择最快变化证据。
    private data class RiseObservation(val windowSeconds: Int, val rateCPerMinute: Double)

    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var activeLevel: TemperatureAlertLevel? = null
    private var warningMatches = 0
    private var criticalMatches = 0
    private var recoveryMatches = 0

    //MARK:更新状态
    //加入最新最高温度样本，结合 BMS 阈值、保护位及多时间窗升温率，返回新警报或恢复事件。
    fun update(
        info: BmsBasicInfo,
        protectionParams: JbdProtectionParams?,
        nowMillis: Long = System.currentTimeMillis()
    ): TemperatureMonitorUpdate {
        val maximumTemperatureC = info.temperaturesC
            .filter { it in MIN_VALID_TEMPERATURE_C..MAX_VALID_TEMPERATURE_C }
            .maxOrNull() ?: return TemperatureMonitorUpdate()
        // 只保留最近一分钟样本；10、30、60 秒升温窗口都从同一队列计算，避免多个计时器产生偏差。
        samples.addLast(nowMillis to maximumTemperatureC)
        while (samples.isNotEmpty() && nowMillis - samples.first().first > SAMPLE_WINDOW_MS) {
            samples.removeFirst()
        }

        val configuredLimit = when {
            // 大于 7A 视为明确充电；微小正电流可能是回收电流，静置时采用充放电阈值中更保守者。
            info.currentA > 7.0 -> protectionParams?.chargeHighTempC
            info.currentA < -0.05 -> protectionParams?.dischargeHighTempC
            else -> listOfNotNull(
                protectionParams?.chargeHighTempC,
                protectionParams?.dischargeHighTempC
            ).minOrNull()
        }
        val criticalThresholdC = min(configuredLimit ?: DEFAULT_CRITICAL_C, MAX_CRITICAL_C)
        // 即使 BMS 被设置成更高保护温度，App 危险阈值也不超过 60℃，保留独立安全兜底。
        val warningThresholdC = (criticalThresholdC - WARNING_MARGIN_C).coerceAtLeast(MIN_WARNING_C)
        val riseObservations = riseObservations(nowMillis, maximumTemperatureC)
        val fastestRise = riseObservations.maxByOrNull { it.rateCPerMinute }
        val rapidCriticalRise = riseObservations
            .filter { it.rateCPerMinute >= RAPID_CRITICAL_RATE_C_PER_MINUTE }
            .maxByOrNull { it.rateCPerMinute }
        val rapidWarningRise = riseObservations
            .filter { it.rateCPerMinute >= RAPID_WARNING_RATE_C_PER_MINUTE }
            .maxByOrNull { it.rateCPerMinute }
        val bmsHighTemperatureProtection = info.protectionMask and ((1 shl 4) or (1 shl 6)) != 0
        val rapidCritical = maximumTemperatureC >= RAPID_CRITICAL_MIN_C &&
            rapidCriticalRise != null
        val rapidWarning = maximumTemperatureC >= RAPID_WARNING_MIN_C &&
            rapidWarningRise != null
        val critical = bmsHighTemperatureProtection || maximumTemperatureC >= criticalThresholdC || rapidCritical
        val warning = maximumTemperatureC >= warningThresholdC || rapidWarning

        criticalMatches = if (critical) criticalMatches + 1 else 0
        warningMatches = if (warning) warningMatches + 1 else 0
        // 普通温升需连续命中多个样本才报警，过滤单个探头毛刺；BMS 已保护时则立即按危险处理。
        val requestedLevel = when {
            bmsHighTemperatureProtection || criticalMatches >= CRITICAL_CONFIRM_SAMPLES -> TemperatureAlertLevel.Critical
            warningMatches >= WARNING_CONFIRM_SAMPLES -> TemperatureAlertLevel.Warning
            else -> null
        }

        if (requestedLevel != null && requestedLevel != activeLevel) {
            // 同一等级只派发一次；危险状态回落到警告时不重复弹框，必须完成恢复确认后才能再次报警。
            if (activeLevel == TemperatureAlertLevel.Critical && requestedLevel == TemperatureAlertLevel.Warning) {
                return TemperatureMonitorUpdate()
            }
            activeLevel = requestedLevel
            recoveryMatches = 0
            val cause = when {
                bmsHighTemperatureProtection -> "BMS 已触发高温保护"
                requestedLevel == TemperatureAlertLevel.Critical -> "温度已达到危险阈值"
                rapidCritical || rapidWarning -> "温度正在快速上升"
                else -> "温度已接近 BMS 高温保护阈值"
            }
            val reportedRise = when {
                rapidCritical -> rapidCriticalRise
                rapidWarning -> rapidWarningRise
                else -> fastestRise
            }
            val riseDescription = reportedRise
                ?.takeIf { it.rateCPerMinute > 0.0 }
                ?.let {
                    "近${it.windowSeconds}秒升温速度约 ${formatTemperature(it.rateCPerMinute)}℃/分钟。"
                }
                .orEmpty()
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
                    message = "$cause，最高温度 ${formatTemperature(maximumTemperatureC)}℃。$riseDescription$action",
                    maximumTemperatureC = maximumTemperatureC,
                    warningThresholdC = warningThresholdC,
                    criticalThresholdC = criticalThresholdC,
                    riseRateCPerMinute = reportedRise?.rateCPerMinute,
                    riseWindowSeconds = reportedRise?.windowSeconds,
                    triggeredAtMillis = nowMillis
                )
            )
        }

        val recovered = activeLevel != null &&
            !bmsHighTemperatureProtection &&
            maximumTemperatureC <= warningThresholdC - RECOVERY_MARGIN_C
        recoveryMatches = if (recovered) recoveryMatches + 1 else 0
        // 恢复阈值比警告阈值再低 3℃并要求连续确认，形成滞回，防止临界温度反复报警。
        if (recoveryMatches >= RECOVERY_CONFIRM_SAMPLES) {
            reset(clearSamples = false)
            return TemperatureMonitorUpdate(recovered = true)
        }
        return TemperatureMonitorUpdate()
    }

    //MARK:重置状态
    //reset 清空该组件维护的临时采样和累计状态，为下一次独立会话重新建立基线。
    fun reset(clearSamples: Boolean = true) {
        activeLevel = null
        warningMatches = 0
        criticalMatches = 0
        recoveryMatches = 0
        if (clearSamples) samples.clear()
    }

    //MARK:计算温升
    //riseObservations 分别计算近 10、30、60 秒有效跨度内的升温速率，跨度不足时不进行外推。
    private fun riseObservations(
        nowMillis: Long,
        currentTemperatureC: Double
    ): List<RiseObservation> = RISE_WINDOWS.mapNotNull { window ->
        val earliestAllowedMillis = nowMillis - window.seconds * 1_000L
        // 选择窗口内最早样本；样本跨度不足时不外推一分钟升温率，避免刚连接时夸大短时波动。
        val reference = samples.firstOrNull { it.first >= earliestAllowedMillis } ?: return@mapNotNull null
        val elapsedMillis = nowMillis - reference.first
        if (elapsedMillis < window.minimumSpanSeconds * 1_000L) return@mapNotNull null
        RiseObservation(
            windowSeconds = window.seconds,
            rateCPerMinute = (currentTemperatureC - reference.second) * 60_000.0 / elapsedMillis
        )
    }

    //MARK:格式化温度
    //formatTemperature 把温度或升温速度固定为一位小数，保持警报正文数值格式一致。
    private fun formatTemperature(value: Double): String = "%.1f".format(value)

    //MARK:常量配置
    //集中定义有效温区、警告与危险阈值、升温速率、多窗口跨度及连续确认和恢复次数。
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
        val RISE_WINDOWS = listOf(
            RiseWindow(seconds = 10, minimumSpanSeconds = 8),
            RiseWindow(seconds = 30, minimumSpanSeconds = 20),
            RiseWindow(seconds = 60, minimumSpanSeconds = 45)
        )
        const val WARNING_CONFIRM_SAMPLES = 3
        const val CRITICAL_CONFIRM_SAMPLES = 2
        const val RECOVERY_CONFIRM_SAMPLES = 5
    }
}
