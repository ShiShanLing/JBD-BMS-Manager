package com.bms.jbdmanager.model

import kotlin.math.abs

enum class HealthDiagnosisLevel {
    Insufficient,
    Normal,
    Observe,
    Warning,
    Critical
}

enum class HealthDiagnosisConfidence {
    Low,
    Medium,
    High
}

data class HealthDiagnosisFinding(
    val level: HealthDiagnosisLevel,
    val title: String,
    val detail: String
)

data class CellHealthDiagnosis(
    val cellNumber: Int,
    val baselineMv: Int,
    val latestMv: Int,
    val absoluteChangeMv: Int,
    /** 去除整组电压共同升降后，该串相对全组平均值的变化。 */
    val relativeDriftMv: Double,
    val latestDeviationFromAverageMv: Double,
    val level: HealthDiagnosisLevel
)

data class BatteryHealthDiagnosis(
    val overallLevel: HealthDiagnosisLevel,
    val confidence: HealthDiagnosisConfidence,
    val summary: String,
    val findings: List<HealthDiagnosisFinding>,
    val cells: List<CellHealthDiagnosis>,
    val baselineFingerprint: FullChargeFingerprint?,
    val latestFingerprint: FullChargeFingerprint?,
    val capacityTestCount: Int,
    val comparableFingerprintCount: Int
)

fun diagnoseBatteryHealth(
    capacityRecords: List<CapacityHealthRecord>,
    fingerprints: List<FullChargeFingerprint>,
    protectionEvents: List<ProtectionEvent>,
    deviceAddress: String? = null
): BatteryHealthDiagnosis {
    val capacity = capacityRecords.filter { it.qualifiedForHealth }.sortedBy { it.recordedAtMillis }
    val orderedFingerprints = fingerprints
        .filter { it.cellVoltagesMv.isNotEmpty() && it.socPercent >= 98 }
        .sortedBy { it.capturedAtMillis }
    val latestFingerprint = orderedFingerprints.lastOrNull()
    val comparable = latestFingerprint?.let { latest ->
        orderedFingerprints.filter { candidate ->
            candidate.cellVoltagesMv.size == latest.cellVoltagesMv.size &&
                abs(candidate.socPercent - latest.socPercent) <= 2 &&
                temperaturesComparable(candidate, latest)
        }
    }.orEmpty()
    val baseline = latestFingerprint?.let { latest ->
        comparable.firstOrNull {
            latest.capturedAtMillis - it.capturedAtMillis >= MINIMUM_COMPARISON_SPAN_MILLIS
        }
    }
    val cells = if (baseline != null) {
        compareCells(baseline, latestFingerprint)
    } else {
        emptyList()
    }

    val findings = buildList {
        capacity.lastOrNull()?.let { record -> add(capacityFinding(record)) }
        if (capacity.isEmpty()) {
            add(
                HealthDiagnosisFinding(
                    HealthDiagnosisLevel.Insufficient,
                    "缺少实测容量",
                    "容量健康度需要至少一次从满充到低压截止的完整放电测试。"
                )
            )
        }

        when {
            latestFingerprint == null -> add(
                HealthDiagnosisFinding(
                    HealthDiagnosisLevel.Insufficient,
                    "缺少满充单体基准",
                    "连接App并充至98%以上后，会自动保存逐串电压。"
                )
            )
            baseline == null -> add(
                HealthDiagnosisFinding(
                    HealthDiagnosisLevel.Insufficient,
                    "单体数据仍在积累",
                    "已有${comparable.size}次可用满充记录，至少间隔7天后才能判断长期变化。"
                )
            )
            else -> add(cellFinding(cells, baseline, latestFingerprint))
        }

        val matchingEvents = protectionEvents.filter {
            deviceAddress == null || it.deviceAddress == null || it.deviceAddress == deviceAddress
        }
        val abnormalCellUndervoltage = matchingEvents.count {
            it.protectionBit == 1 && it.severity == ProtectionEventSeverity.Critical
        }
        if (abnormalCellUndervoltage > 0) {
            add(
                HealthDiagnosisFinding(
                    if (abnormalCellUndervoltage >= 2) HealthDiagnosisLevel.Critical else HealthDiagnosisLevel.Warning,
                    "出现单体提前欠压",
                    "历史中记录到${abnormalCellUndervoltage}次异常单体欠压，应结合最弱单体和容量测试检查。"
                )
            )
        }
        val thermalEvents = matchingEvents.count { it.protectionBit in 4..7 }
        if (thermalEvents > 0) {
            add(
                HealthDiagnosisFinding(
                    HealthDiagnosisLevel.Warning,
                    "出现温度保护",
                    "历史中记录到${thermalEvents}次充放电温度保护，频繁高温会加速电池老化。"
                )
            )
        }
    }

    val decisiveLevels = findings.map { it.level }.filter { it != HealthDiagnosisLevel.Insufficient }
    val overall = decisiveLevels.maxByOrNull { it.ordinal } ?: HealthDiagnosisLevel.Insufficient
    val spanMillis = if (comparable.size >= 2) {
        comparable.last().capturedAtMillis - comparable.first().capturedAtMillis
    } else 0L
    val confidence = when {
        capacity.size >= 2 && comparable.size >= 3 && spanMillis >= 30L * DAY_MILLIS -> HealthDiagnosisConfidence.High
        capacity.isNotEmpty() || baseline != null -> HealthDiagnosisConfidence.Medium
        else -> HealthDiagnosisConfidence.Low
    }
    val hasIncompleteEvidence = findings.any { it.level == HealthDiagnosisLevel.Insufficient }
    val summary = when {
        overall == HealthDiagnosisLevel.Normal && hasIncompleteEvidence -> "已测项目正常，仍有数据待积累"
        else -> when (overall) {
        HealthDiagnosisLevel.Insufficient -> "数据不足，继续积累"
        HealthDiagnosisLevel.Normal -> "目前未发现明显异常"
        HealthDiagnosisLevel.Observe -> "存在轻微变化，建议持续观察"
        HealthDiagnosisLevel.Warning -> "发现需要关注的健康变化"
        HealthDiagnosisLevel.Critical -> "发现明显异常，建议尽快复核"
        }
    }
    return BatteryHealthDiagnosis(
        overallLevel = overall,
        confidence = confidence,
        summary = summary,
        findings = findings.sortedByDescending { it.level.ordinal },
        cells = cells.sortedWith(
            compareByDescending<CellHealthDiagnosis> { it.level.ordinal }
                .thenBy { it.relativeDriftMv }
        ),
        baselineFingerprint = baseline,
        latestFingerprint = latestFingerprint,
        capacityTestCount = capacity.size,
        comparableFingerprintCount = comparable.size
    )
}

private fun temperaturesComparable(first: FullChargeFingerprint, second: FullChargeFingerprint): Boolean {
    val firstTemperature = first.maximumTemperatureC ?: return true
    val secondTemperature = second.maximumTemperatureC ?: return true
    return abs(firstTemperature - secondTemperature) <= 7.0
}

private fun compareCells(
    baseline: FullChargeFingerprint,
    latest: FullChargeFingerprint
): List<CellHealthDiagnosis> {
    val baselineAverage = baseline.cellVoltagesMv.average()
    val latestAverage = latest.cellVoltagesMv.average()
    return baseline.cellVoltagesMv.indices.map { index ->
        val baselineMv = baseline.cellVoltagesMv[index]
        val latestMv = latest.cellVoltagesMv[index]
        val latestDeviation = latestMv - latestAverage
        val relativeDrift = latestDeviation - (baselineMv - baselineAverage)
        val level = when {
            latestDeviation <= -60.0 && relativeDrift <= -35.0 -> HealthDiagnosisLevel.Critical
            latestDeviation <= -40.0 && relativeDrift <= -20.0 -> HealthDiagnosisLevel.Warning
            relativeDrift <= -15.0 -> HealthDiagnosisLevel.Observe
            else -> HealthDiagnosisLevel.Normal
        }
        CellHealthDiagnosis(
            cellNumber = index + 1,
            baselineMv = baselineMv,
            latestMv = latestMv,
            absoluteChangeMv = latestMv - baselineMv,
            relativeDriftMv = relativeDrift,
            latestDeviationFromAverageMv = latestDeviation,
            level = level
        )
    }
}

private fun capacityFinding(record: CapacityHealthRecord): HealthDiagnosisFinding {
    val level = when {
        record.sohPercent < 75.0 -> HealthDiagnosisLevel.Critical
        record.sohPercent < 80.0 -> HealthDiagnosisLevel.Warning
        record.sohPercent < 90.0 -> HealthDiagnosisLevel.Observe
        else -> HealthDiagnosisLevel.Normal
    }
    return HealthDiagnosisFinding(
        level = level,
        title = "实测容量 ${formatDiagnosisNumber(record.sohPercent)}%",
        detail = "实际放出${formatDiagnosisNumber(record.measuredDischargeAh)}Ah，BMS总容量基准${formatDiagnosisNumber(record.ratedCapacityAh)}Ah。"
    )
}

private fun cellFinding(
    cells: List<CellHealthDiagnosis>,
    baseline: FullChargeFingerprint,
    latest: FullChargeFingerprint
): HealthDiagnosisFinding {
    val worst = cells.sortedWith(
        compareByDescending<CellHealthDiagnosis> { it.level.ordinal }
            .thenBy { it.relativeDriftMv }
    ).firstOrNull()
    val level = worst?.level ?: HealthDiagnosisLevel.Normal
    val deltaChange = (latest.cellDeltaMv ?: 0) - (baseline.cellDeltaMv ?: 0)
    return when (level) {
        HealthDiagnosisLevel.Critical, HealthDiagnosisLevel.Warning, HealthDiagnosisLevel.Observe ->
            HealthDiagnosisFinding(
                level,
                "${worst?.cellNumber ?: 0}串相对其他单体持续偏低",
                "去除整组共同升降后相对基准变化${signed(worst?.relativeDriftMv ?: 0.0)}mV；当前比全组平均低${formatDiagnosisNumber(abs(worst?.latestDeviationFromAverageMv ?: 0.0))}mV。"
            )
        else -> HealthDiagnosisFinding(
            HealthDiagnosisLevel.Normal,
            "单体一致性暂未发现明显恶化",
            "两次可比满充记录的整组压差变化${signed(deltaChange.toDouble())}mV。"
        )
    }
}

private fun signed(value: Double): String =
    "${if (value > 0) "+" else ""}${formatDiagnosisNumber(value)}"

private fun formatDiagnosisNumber(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
private const val MINIMUM_COMPARISON_SPAN_MILLIS = 7 * DAY_MILLIS
