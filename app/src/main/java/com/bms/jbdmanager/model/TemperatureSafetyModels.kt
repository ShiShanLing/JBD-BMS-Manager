package com.bms.jbdmanager.model

//MARK:温度警报
//TemperatureAlertLevel 枚举温度警报的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class TemperatureAlertLevel {
    Warning,
    Critical
}

//MARK:安全警报
//TemperatureSafetyAlert 将温度安全警报相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class TemperatureSafetyAlert(
    val id: Long,
    val level: TemperatureAlertLevel,
    val title: String,
    val message: String,
    val maximumTemperatureC: Double,
    val warningThresholdC: Double,
    val criticalThresholdC: Double,
    val riseRateCPerMinute: Double?,
    val riseWindowSeconds: Int?,
    val triggeredAtMillis: Long
)

//MARK:温度更新
//TemperatureMonitorUpdate 将温度更新相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class TemperatureMonitorUpdate(
    val alert: TemperatureSafetyAlert? = null,
    val recovered: Boolean = false
)
