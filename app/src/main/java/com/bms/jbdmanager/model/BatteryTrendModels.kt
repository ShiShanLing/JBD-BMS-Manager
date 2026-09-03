package com.bms.jbdmanager.model

data class BatteryTrendPoint(
    val timestampMillis: Long,
    val totalVoltageV: Double,
    val currentA: Double,
    val socPercent: Double,
    val maximumTemperatureC: Double?,
    val cellDeltaMv: Double?,
    val minimumCellMv: Double?
)

data class FullChargeFingerprint(
    val capturedAtMillis: Long,
    val totalVoltageV: Double,
    val socPercent: Int,
    val maximumTemperatureC: Double?,
    val cellVoltagesMv: List<Int>
) {
    val minimumCellMv: Int? get() = cellVoltagesMv.minOrNull()
    val maximumCellMv: Int? get() = cellVoltagesMv.maxOrNull()
    val cellDeltaMv: Int? get() = minimumCellMv?.let { minimum -> maximumCellMv?.minus(minimum) }
}

data class FullChargeDeltaSample(
    val capturedAtMillis: Long,
    val cellDeltaMv: Int,
    val totalVoltageV: Double,
    val currentA: Double? = null,
    val socPercent: Int = 100,
    val maximumTemperatureC: Double? = null,
    val remainingCapacityAh: Double? = null
)

enum class FullChargeDeltaDirection {
    Insufficient,
    Improving,
    Stable,
    Worsening
}

data class FullChargeDeltaTrend(
    val samples: List<FullChargeDeltaSample>,
    val latestDeltaMv: Int?,
    val baselineDeltaMv: Int?,
    val changeMv: Double?,
    val direction: FullChargeDeltaDirection,
    val summary: String
)

fun evaluateFullChargeDeltaTrend(samples: List<FullChargeDeltaSample>): FullChargeDeltaTrend {
    val ordered = samples.sortedBy { it.capturedAtMillis }
    if (ordered.size < 2) {
        val latest = ordered.lastOrNull()?.cellDeltaMv
        return FullChargeDeltaTrend(
            samples = ordered,
            latestDeltaMv = latest,
            baselineDeltaMv = ordered.firstOrNull()?.cellDeltaMv,
            changeMv = null,
            direction = FullChargeDeltaDirection.Insufficient,
            summary = if (ordered.isEmpty()) {
                "连接电池且接近满充（SOC≥99%，或剩余容量接近满充Ah）时会记录压差。至少两次后才能判断是否在变好。"
            } else {
                "已记录一次满充压差 ${latest}mV。再次连接且仍接近满电后，即可对比趋势。"
            }
        )
    }
    val baselineWindow = if (ordered.size >= 4) {
        ordered.take((ordered.size / 3).coerceAtLeast(1))
    } else {
        listOf(ordered.first())
    }
    val latestWindow = if (ordered.size >= 4) {
        ordered.takeLast((ordered.size / 3).coerceAtLeast(1))
    } else {
        listOf(ordered.last())
    }
    val baseline = baselineWindow.map { it.cellDeltaMv.toDouble() }.average()
    val latestAverage = latestWindow.map { it.cellDeltaMv.toDouble() }.average()
    val change = latestAverage - baseline
    val direction = when {
        change <= -FULL_CHARGE_DELTA_STABLE_BAND_MV -> FullChargeDeltaDirection.Improving
        change >= FULL_CHARGE_DELTA_STABLE_BAND_MV -> FullChargeDeltaDirection.Worsening
        else -> FullChargeDeltaDirection.Stable
    }
    val summary = when (direction) {
        FullChargeDeltaDirection.Improving ->
            "满充压差由 ${formatFullChargeDelta(baseline)}mV 降到 ${formatFullChargeDelta(latestAverage)}mV，电芯一致性在向好。"
        FullChargeDeltaDirection.Stable ->
            "满充压差约 ${ordered.last().cellDeltaMv}mV，较首次变化${signedFullChargeDelta(change)}mV，目前基本稳定。"
        FullChargeDeltaDirection.Worsening ->
            "满充压差由 ${formatFullChargeDelta(baseline)}mV 升到 ${formatFullChargeDelta(latestAverage)}mV，均衡在变差，建议持续观察。"
        FullChargeDeltaDirection.Insufficient -> ""
    }
    return FullChargeDeltaTrend(
        samples = ordered,
        latestDeltaMv = ordered.last().cellDeltaMv,
        baselineDeltaMv = ordered.first().cellDeltaMv,
        changeMv = change,
        direction = direction,
        summary = summary
    )
}

private fun signedFullChargeDelta(value: Double): String =
    "${if (value > 0) "+" else ""}${formatFullChargeDelta(value)}"

private fun formatFullChargeDelta(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

private const val FULL_CHARGE_DELTA_STABLE_BAND_MV = 5.0

enum class BatteryTrendRange(val label: String, val durationMillis: Long?) {
    CurrentTrip("本次", null),
    OneHour("1小时", 60 * 60 * 1_000L),
    OneDay("24小时", 24 * 60 * 60 * 1_000L),
    SevenDays("7天", 7 * 24 * 60 * 60 * 1_000L),
    ThirtyDays("30天", 30 * 24 * 60 * 60 * 1_000L)
}

data class BatteryTrendState(
    val range: BatteryTrendRange = BatteryTrendRange.OneDay,
    val points: List<BatteryTrendPoint> = emptyList(),
    val fullChargeFingerprints: List<FullChargeFingerprint> = emptyList(),
    val fullChargeDeltas: List<FullChargeDeltaSample> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)
