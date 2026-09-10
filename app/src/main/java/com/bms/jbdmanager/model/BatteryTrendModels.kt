package com.bms.jbdmanager.model

//MARK:电池趋势
//BatteryTrendPoint 表示一条独立的电池趋势采样点，包含排序、统计、持久化或报告展示所需字段。
data class BatteryTrendPoint(
    val timestampMillis: Long,
    val totalVoltageV: Double,
    val currentA: Double,
    val socPercent: Double,
    val maximumTemperatureC: Double?,
    val cellDeltaMv: Double?,
    val minimumCellMv: Double?
)

//MARK:满充状态指纹
//FullChargeFingerprint 表示一条独立的满充充电特征样本，包含排序、统计、持久化或报告展示所需字段。
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

//MARK:压差样本
//FullChargeDeltaSample 表示一条独立的满充充电压差样本，包含排序、统计、持久化或报告展示所需字段。
data class FullChargeDeltaSample(
    val capturedAtMillis: Long,
    val cellDeltaMv: Int,
    val totalVoltageV: Double,
    val currentA: Double? = null,
    val socPercent: Int = 100,
    val maximumTemperatureC: Double? = null,
    val remainingCapacityAh: Double? = null
)

//MARK:压差变化方向
//FullChargeDeltaDirection 枚举满充充电压差的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class FullChargeDeltaDirection {
    Insufficient,
    Improving,
    Stable,
    Worsening
}

//MARK:压差趋势
//FullChargeDeltaTrend 将满充充电压差趋势相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class FullChargeDeltaTrend(
    val samples: List<FullChargeDeltaSample>,
    val latestDeltaMv: Int?,
    val baselineDeltaMv: Int?,
    val changeMv: Double?,
    val direction: FullChargeDeltaDirection,
    val summary: String
)

//MARK:评估压差趋势
//evaluateFullChargeDeltaTrend 按时间比较多次满充压差样本，计算变化量、方向和可读趋势结论。
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

//MARK:压差数值符号
//signedFullChargeDelta 把满充充电压差映射为统一名称、格式或默认结构，供存储和界面共同复用。
private fun signedFullChargeDelta(value: Double): String =
    "${if (value > 0) "+" else ""}${formatFullChargeDelta(value)}"

//MARK:格式化充电压
//formatFullChargeDelta 把满充充电压差映射为统一名称、格式或默认结构，供存储和界面共同复用。
private fun formatFullChargeDelta(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

private const val FULL_CHARGE_DELTA_STABLE_BAND_MV = 5.0

//MARK:趋势时间范围
//BatteryTrendRange 枚举电池趋势续航的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class BatteryTrendRange(val label: String, val durationMillis: Long?) {
    CurrentTrip("本次", null),
    OneHour("1小时", 60 * 60 * 1_000L),
    OneDay("24小时", 24 * 60 * 60 * 1_000L),
    SevenDays("7天", 7 * 24 * 60 * 60 * 1_000L),
    ThirtyDays("30天", 30 * 24 * 60 * 60 * 1_000L)
}

//MARK:趋势状态
//BatteryTrendState 保存电池趋势状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
data class BatteryTrendState(
    val range: BatteryTrendRange = BatteryTrendRange.OneDay,
    val points: List<BatteryTrendPoint> = emptyList(),
    val fullChargeFingerprints: List<FullChargeFingerprint> = emptyList(),
    val fullChargeDeltas: List<FullChargeDeltaSample> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)
