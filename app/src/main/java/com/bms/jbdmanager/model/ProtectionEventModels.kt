package com.bms.jbdmanager.model

import kotlin.math.abs

enum class ProtectionEventSeverity {
    Expected,
    Warning,
    Critical
}

data class ProtectionEvent(
    val id: Long,
    val protectionBit: Int,
    val title: String,
    val startedAtMillis: Long,
    val resolvedAtMillis: Long? = null,
    val severity: ProtectionEventSeverity,
    val summary: String,
    val stateOfChargePercent: Int,
    val totalVoltageV: Double,
    val currentA: Double,
    val minimumCellMv: Int? = null,
    val maximumCellMv: Int? = null,
    val cellDeltaMv: Int? = null,
    val maximumTemperatureC: Double? = null,
    val deviceAddress: String? = null,
    val deviceName: String? = null
) {
    val isActive: Boolean get() = resolvedAtMillis == null
}

data class ProtectionClassification(
    val severity: ProtectionEventSeverity,
    val summary: String
)

private val protectionNames = listOf(
    "单体过压", "单体欠压", "总压过高", "总压过低",
    "充电高温", "充电低温", "放电高温", "放电低温",
    "充电过流", "放电过流", "短路保护", "前端芯片异常",
    "软件关闭 MOS", "充电 MOS 异常", "放电 MOS 异常"
)

fun protectionName(bit: Int): String = protectionNames.getOrNull(bit) ?: "未知保护 ${bit + 1}"

fun classifyProtectionEvent(bit: Int, info: BmsBasicInfo, cells: CellSummary?): ProtectionClassification {
    if (bit == 1 || bit == 3) {
        val deltaMv = cells?.deltaMv
        val lowSoc = info.stateOfChargePercent <= 5
        val largeVoltageSag = info.currentA < -20.0
        val weakCellEvidence = bit == 1 && deltaMv != null && deltaMv >= 150
        return when {
            weakCellEvidence -> ProtectionClassification(
                ProtectionEventSeverity.Critical,
                "单体提前欠压且压差较大，建议检查电芯一致性"
            )
            lowSoc -> ProtectionClassification(
                ProtectionEventSeverity.Expected,
                "电量接近耗尽时触发，按正常低电量截止记录"
            )
            largeVoltageSag -> ProtectionClassification(
                ProtectionEventSeverity.Warning,
                "较大放电电流下触发，可能是负载瞬时压降"
            )
            else -> ProtectionClassification(
                ProtectionEventSeverity.Critical,
                "SOC 尚未接近耗尽却触发欠压，需要检查容量、连接和电芯状态"
            )
        }
    }
    return when (bit) {
        12 -> ProtectionClassification(
            ProtectionEventSeverity.Warning,
            "BMS 软件关闭 MOS，需结合当时运行状态确认原因"
        )
        else -> ProtectionClassification(
            ProtectionEventSeverity.Critical,
            "BMS 已触发${protectionName(bit)}"
        )
    }
}

fun resolveProtectionEvent(event: ProtectionEvent, resolvedAtMillis: Long): ProtectionEvent {
    val durationMillis = (resolvedAtMillis - event.startedAtMillis).coerceAtLeast(0L)
    val shortLoadSag = event.protectionBit in listOf(1, 3) &&
        event.severity == ProtectionEventSeverity.Warning &&
        abs(event.currentA) >= 20.0 && durationMillis <= 60_000L
    return event.copy(
        resolvedAtMillis = resolvedAtMillis,
        summary = if (shortLoadSag) {
            "大负载下短时欠压，${durationMillis / 1_000}秒后恢复"
        } else {
            event.summary
        }
    )
}
