package com.bms.jbdmanager.model

//MARK:健康记录
//CapacityHealthRecord 表示一条独立的容量健康记录，包含排序、统计、持久化或报告展示所需字段。
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

//MARK:容量记录来源
//CapacityHealthRecordSource 枚举容量健康记录的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class CapacityHealthRecordSource { Manual, Automatic }
