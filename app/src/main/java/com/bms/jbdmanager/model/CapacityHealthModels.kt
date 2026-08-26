package com.bms.jbdmanager.model

data class CapacityHealthRecord(
    val id: Long,
    val recordedAtMillis: Long,
    val measuredDischargeAh: Double,
    val ratedCapacityAh: Double,
    val measuredDischargeWh: Double? = null,
    val cycleCount: Int? = null,
    val averageTemperatureC: Double? = null,
    val note: String = "",
    val source: CapacityHealthRecordSource = CapacityHealthRecordSource.Manual,
    val qualifiedForHealth: Boolean = true,
    val qualityPercent: Double? = null
) {
    val sohPercent: Double
        get() = (measuredDischargeAh / ratedCapacityAh * 100.0).coerceIn(0.0, 150.0)
}

enum class CapacityHealthRecordSource { Manual, Automatic }
