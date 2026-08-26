package com.bms.jbdmanager.model

enum class TemperatureAlertLevel {
    Warning,
    Critical
}

data class TemperatureSafetyAlert(
    val id: Long,
    val level: TemperatureAlertLevel,
    val title: String,
    val message: String,
    val maximumTemperatureC: Double,
    val warningThresholdC: Double,
    val criticalThresholdC: Double,
    val riseRateCPerMinute: Double?,
    val triggeredAtMillis: Long
)

data class TemperatureMonitorUpdate(
    val alert: TemperatureSafetyAlert? = null,
    val recovered: Boolean = false
)
