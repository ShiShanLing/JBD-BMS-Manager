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
    val isLoading: Boolean = false,
    val message: String? = null
)
