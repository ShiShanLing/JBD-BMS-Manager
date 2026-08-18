package com.bms.jbdmanager.trip

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.model.RangeTestState
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.defaultSpeedRangeStats

internal class TripStateStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(value: TripState) {
        preferences.edit()
            .putBoolean("is_tracking", value.isTracking)
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


    fun load(): TripState {
        if (!preferences.contains("started_at")) return TripState()
        return TripState(
            isTracking = preferences.getBoolean("is_tracking", false),
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


    private companion object {
        const val PREFERENCES_NAME = "jbd_trip_tracking"
    }
}
