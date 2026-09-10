package com.bms.jbdmanager.trip

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.model.RangeTestState
import com.bms.jbdmanager.model.RegenerationPeak
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.TripTrackingMode
import com.bms.jbdmanager.model.defaultSpeedRangeStats

//MARK:行程状态存储
//TripStateStore 封装本地持久化、兼容解析和写回规则，用于处理行程状态。
internal class TripStateStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    //MARK:保存状态
    //将活动行程、累计电量、续航测试和分速度样本一次性保存，供进程重启后恢复。
    fun save(value: TripState) {
        preferences.edit()
            .putBoolean("is_tracking", value.isTracking)
            .putString("tracking_mode", value.trackingMode.name)
            .putLong("started_at", value.startedAtMillis ?: -1L)
            .putString("distance_m", value.distanceMeters.toString())
            .putInt("start_soc", value.startSocPercent ?: -1)
            .putInt("current_soc", value.currentSocPercent ?: -1)
            .putString("start_ah", value.startRemainingAh?.toString())
            .putString("current_ah", value.currentRemainingAh?.toString())
            .putString("integrated_ah", value.integratedConsumedAh.toString())
            .putString("integrated_wh", value.integratedConsumedWh.toString())
            .remove("speed_kmh")
            .putFloat("accuracy_m", value.locationAccuracyMeters ?: -1f)
            .putInt("valid_points", value.validLocationPoints)
            .putLong("last_location_at", value.lastLocationAtMillis ?: -1L)
            .putString("gps_message", value.gpsMessage)
            .putInt("mileage_countdown_target_km", value.mileageCountdownTargetKm)
            .putLong("mileage_countdown_reached_at", value.mileageCountdownReachedAtMillis ?: -1L)
            .putBoolean("mileage_countdown_acknowledged", value.mileageCountdownAcknowledged)
            .putString("regen_current_a", value.maximumRegeneration?.currentA?.toString())
            .putString("regen_power_w", value.maximumRegeneration?.powerW?.toString())
            .putString("regen_speed_kmh", value.maximumRegeneration?.speedKmh?.toString())
            .putLong("regen_recorded_at", value.maximumRegeneration?.recordedAtMillis ?: -1L)
            .putBoolean("range_active", value.rangeTest.isActive)
            .putInt("range_target_speed", value.rangeTest.targetSpeedKmh)
            .putInt("range_tolerance", value.rangeTest.speedToleranceKmh)
            .putLong("range_started_at", value.rangeTest.startedAtMillis ?: -1L)
            .putLong("range_finished_at", value.rangeTest.finishedAtMillis ?: -1L)
            .putString("range_distance_m", value.rangeTest.effectiveDistanceMeters.toString())
            .putString("range_duration_s", value.rangeTest.effectiveDurationSeconds.toString())
            .putString("range_consumed_ah", value.rangeTest.consumedAh.toString())
            .putString("range_consumed_wh", value.rangeTest.consumedWh.toString())
            .putInt("range_start_soc", value.rangeTest.startSocPercent ?: -1)
            .putInt("range_current_soc", value.rangeTest.currentSocPercent ?: -1)
            .putString("range_start_ah", value.rangeTest.startRemainingAh?.toString())
            .putString("range_current_ah", value.rangeTest.currentRemainingAh?.toString())
            .also { editor ->
                value.speedRangeStats.forEach { stats ->
                    val prefix = "speed_${stats.targetSpeedKmh}_"
                    editor.putString("${prefix}distance_m", stats.effectiveDistanceMeters.toString())
                    editor.putString("${prefix}duration_s", stats.effectiveDurationSeconds.toString())
                    editor.putString("${prefix}consumed_ah", stats.consumedAh.toString())
                    editor.putString("${prefix}consumed_wh", stats.consumedWh.toString())
                }
            }
            .apply()
    }


    //MARK:读取记录
    //读取上次保存的行程状态并逐字段恢复；缺失的新字段使用默认值以兼容旧版本。
    fun load(): TripState {
        if (!preferences.contains("started_at")) return TripState()
        return TripState(
            isTracking = preferences.getBoolean("is_tracking", false),
            trackingMode = preferences.getString("tracking_mode", null)
                ?.let { stored -> runCatching { TripTrackingMode.valueOf(stored) }.getOrNull() }
                ?: TripTrackingMode.Bms,
            startedAtMillis = preferences.getLong("started_at", -1L).takeIf { it >= 0L },
            distanceMeters = preferences.getString("distance_m", null)?.toDoubleOrNull() ?: 0.0,
            startSocPercent = preferences.getInt("start_soc", -1).takeIf { it >= 0 },
            currentSocPercent = preferences.getInt("current_soc", -1).takeIf { it >= 0 },
            startRemainingAh = preferences.getString("start_ah", null)?.toDoubleOrNull(),
            currentRemainingAh = preferences.getString("current_ah", null)?.toDoubleOrNull(),
            integratedConsumedAh = preferences.getString("integrated_ah", null)?.toDoubleOrNull() ?: 0.0,
            integratedConsumedWh = preferences.getString("integrated_wh", null)?.toDoubleOrNull() ?: 0.0,
            currentSpeedKmh = 0.0,
            locationAccuracyMeters = preferences.getFloat("accuracy_m", -1f).takeIf { it >= 0f },
            validLocationPoints = preferences.getInt("valid_points", 0),
            lastLocationAtMillis = preferences.getLong("last_location_at", -1L).takeIf { it >= 0L },
            gpsMessage = preferences.getString("gps_message", null) ?: "等待恢复行程",
            mileageCountdownTargetKm = preferences.getInt("mileage_countdown_target_km", 30)
                .coerceIn(5, 200),
            mileageCountdownReachedAtMillis = preferences
                .getLong("mileage_countdown_reached_at", -1L)
                .takeIf { it >= 0L },
            mileageCountdownAcknowledged = preferences.getBoolean(
                "mileage_countdown_acknowledged",
                false
            ),
            maximumRegeneration = preferences.getString("regen_power_w", null)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { powerW ->
                    RegenerationPeak(
                        currentA = preferences.getString("regen_current_a", null)?.toDoubleOrNull() ?: 0.0,
                        powerW = powerW,
                        speedKmh = preferences.getString("regen_speed_kmh", null)?.toDoubleOrNull() ?: 0.0,
                        recordedAtMillis = preferences.getLong("regen_recorded_at", -1L).takeIf { it >= 0L } ?: 0L
                    )
                },
            rangeTest = RangeTestState(
                isActive = preferences.getBoolean("range_active", false),
                targetSpeedKmh = preferences.getInt("range_target_speed", 40),
                speedToleranceKmh = preferences.getInt("range_tolerance", 5),
                startedAtMillis = preferences.getLong("range_started_at", -1L).takeIf { it >= 0L },
                finishedAtMillis = preferences.getLong("range_finished_at", -1L).takeIf { it >= 0L },
                effectiveDistanceMeters = preferences.getString("range_distance_m", null)?.toDoubleOrNull() ?: 0.0,
                effectiveDurationSeconds = preferences.getString("range_duration_s", null)?.toDoubleOrNull() ?: 0.0,
                consumedAh = preferences.getString("range_consumed_ah", null)?.toDoubleOrNull() ?: 0.0,
                consumedWh = preferences.getString("range_consumed_wh", null)?.toDoubleOrNull() ?: 0.0,
                startSocPercent = preferences.getInt("range_start_soc", -1).takeIf { it >= 0 },
                currentSocPercent = preferences.getInt("range_current_soc", -1).takeIf { it >= 0 },
                startRemainingAh = preferences.getString("range_start_ah", null)?.toDoubleOrNull(),
                currentRemainingAh = preferences.getString("range_current_ah", null)?.toDoubleOrNull()
            ),
            speedRangeStats = defaultSpeedRangeStats().map { stats ->
                val prefix = "speed_${stats.targetSpeedKmh}_"
                stats.copy(
                    effectiveDistanceMeters = preferences.getString("${prefix}distance_m", null)?.toDoubleOrNull() ?: 0.0,
                    effectiveDurationSeconds = preferences.getString("${prefix}duration_s", null)?.toDoubleOrNull() ?: 0.0,
                    consumedAh = preferences.getString("${prefix}consumed_ah", null)?.toDoubleOrNull() ?: 0.0,
                    consumedWh = preferences.getString("${prefix}consumed_wh", null)?.toDoubleOrNull() ?: 0.0
                )
            }
        )
    }


    //MARK:常量配置
    //集中声明行程状态的偏好文件名及字段键，键名变化时必须保留旧数据读取兼容。
    private companion object {
        const val PREFERENCES_NAME = "jbd_trip_tracking"
    }
}
