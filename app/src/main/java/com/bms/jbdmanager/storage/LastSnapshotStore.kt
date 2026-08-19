package com.bms.jbdmanager.storage

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.LastBmsSnapshot
import com.bms.jbdmanager.model.TripState

internal class LastSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(state: BmsUiState, nowMillis: Long = System.currentTimeMillis()): LastBmsSnapshot? {
        val info = state.basicInfo ?: return null
        val snapshot = LastBmsSnapshot(
            savedAtMillis = nowMillis,
            deviceAddress = state.connectedAddress,
            deviceName = state.connectedName,
            modelName = state.modelName,
            chipType = state.chipType,
            protocolProfile = state.protocolProfile,
            detectedProtocol = state.detectedProtocol,
            basicInfo = info,
            cells = state.cells,
            gpsSpeed = state.gpsSpeed,
            trip = state.trip.copy(isTracking = false, currentSpeedKmh = 0.0)
        )
        write(snapshot)
        return snapshot
    }

    fun load(): LastBmsSnapshot? {
        if (!preferences.getBoolean(KEY_EXISTS, false)) return null
        val savedAt = preferences.getLong(KEY_SAVED_AT, -1L).takeIf { it >= 0L } ?: return null
        val basic = BmsBasicInfo(
            totalVoltageV = preferences.double(KEY_TOTAL_VOLTAGE) ?: return null,
            currentA = preferences.double(KEY_CURRENT) ?: 0.0,
            remainingCapacityAh = preferences.double(KEY_REMAINING_AH) ?: return null,
            nominalCapacityAh = preferences.double(KEY_NOMINAL_AH) ?: return null,
            fullChargeCapacityAh = preferences.double(KEY_FULL_CHARGE_AH),
            stateOfChargePercent = preferences.getInt(KEY_SOC, 0).coerceIn(0, 100),
            cycleCount = preferences.getInt(KEY_CYCLES, 0),
            temperaturesC = preferences.getString(KEY_TEMPERATURES, null).toDoubleList(),
            cellCount = preferences.getInt(KEY_CELL_COUNT, 0),
            chargeMosEnabled = preferences.getBoolean(KEY_CHARGE_MOS, false),
            dischargeMosEnabled = preferences.getBoolean(KEY_DISCHARGE_MOS, false),
            balancingMask = preferences.getLong(KEY_BALANCING_MASK, 0L),
            protectionMask = preferences.getInt(KEY_PROTECTION_MASK, 0),
            alarmMask = preferences.optionalInt(KEY_ALARM_MASK),
            softwareVersion = preferences.getString(KEY_SOFTWARE_VERSION, null).orEmpty(),
            productionDate = preferences.getString(KEY_PRODUCTION_DATE, null),
            humidityPercent = preferences.optionalInt(KEY_HUMIDITY),
            balancingCurrentMa = preferences.optionalInt(KEY_BALANCING_CURRENT),
            updatedAtMillis = preferences.getLong(KEY_DATA_UPDATED_AT, savedAt)
        )
        val cells = preferences.getString(KEY_CELLS, null)
            ?.takeIf(String::isNotBlank)
            ?.split(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.takeIf(List<Int>::isNotEmpty)
            ?.let { CellSummary(it, preferences.getLong(KEY_CELLS_UPDATED_AT, savedAt)) }
        val trip = TripState(
            isTracking = false,
            startedAtMillis = preferences.optionalLong(KEY_TRIP_STARTED_AT),
            distanceMeters = preferences.double(KEY_TRIP_DISTANCE) ?: 0.0,
            startSocPercent = preferences.optionalInt(KEY_TRIP_START_SOC),
            currentSocPercent = preferences.optionalInt(KEY_TRIP_CURRENT_SOC),
            startRemainingAh = preferences.double(KEY_TRIP_START_AH),
            currentRemainingAh = preferences.double(KEY_TRIP_CURRENT_AH),
            integratedConsumedAh = preferences.double(KEY_TRIP_CONSUMED_AH) ?: 0.0,
            integratedConsumedWh = preferences.double(KEY_TRIP_CONSUMED_WH) ?: 0.0,
            currentA = preferences.double(KEY_TRIP_CURRENT) ?: 0.0,
            currentSpeedKmh = 0.0,
            locationAccuracyMeters = preferences.optionalFloat(KEY_TRIP_ACCURACY),
            validLocationPoints = preferences.getInt(KEY_TRIP_POINTS, 0),
            lastLocationAtMillis = preferences.optionalLong(KEY_TRIP_LAST_LOCATION),
            gpsMessage = "最后状态保存时的行程"
        )
        return LastBmsSnapshot(
            savedAtMillis = savedAt,
            deviceAddress = preferences.getString(KEY_DEVICE_ADDRESS, null),
            deviceName = preferences.getString(KEY_DEVICE_NAME, null),
            modelName = preferences.getString(KEY_MODEL_NAME, null),
            chipType = preferences.getString(KEY_CHIP_TYPE, null),
            protocolProfile = preferences.getString(KEY_PROTOCOL_PROFILE, null) ?: "未记录",
            detectedProtocol = preferences.getString(KEY_DETECTED_PROTOCOL, null),
            basicInfo = basic,
            cells = cells,
            gpsSpeed = GpsSpeedState(
                currentKmh = preferences.double(KEY_GPS_CURRENT) ?: 0.0,
                average5SecondsKmh = preferences.double(KEY_GPS_AVERAGE) ?: 0.0,
                maximumKmh = preferences.double(KEY_GPS_MAXIMUM) ?: 0.0
            ),
            trip = trip
        )
    }

    private fun write(snapshot: LastBmsSnapshot) {
        val info = snapshot.basicInfo
        val trip = snapshot.trip
        preferences.edit().clear()
            .putBoolean(KEY_EXISTS, true)
            .putLong(KEY_SAVED_AT, snapshot.savedAtMillis)
            .putString(KEY_DEVICE_ADDRESS, snapshot.deviceAddress)
            .putString(KEY_DEVICE_NAME, snapshot.deviceName)
            .putString(KEY_MODEL_NAME, snapshot.modelName)
            .putString(KEY_CHIP_TYPE, snapshot.chipType)
            .putString(KEY_PROTOCOL_PROFILE, snapshot.protocolProfile)
            .putString(KEY_DETECTED_PROTOCOL, snapshot.detectedProtocol)
            .putDouble(KEY_TOTAL_VOLTAGE, info.totalVoltageV)
            .putDouble(KEY_CURRENT, info.currentA)
            .putDouble(KEY_REMAINING_AH, info.remainingCapacityAh)
            .putDouble(KEY_NOMINAL_AH, info.nominalCapacityAh)
            .putOptionalDouble(KEY_FULL_CHARGE_AH, info.fullChargeCapacityAh)
            .putInt(KEY_SOC, info.stateOfChargePercent)
            .putInt(KEY_CYCLES, info.cycleCount)
            .putString(KEY_TEMPERATURES, info.temperaturesC.joinToString(","))
            .putInt(KEY_CELL_COUNT, info.cellCount)
            .putBoolean(KEY_CHARGE_MOS, info.chargeMosEnabled)
            .putBoolean(KEY_DISCHARGE_MOS, info.dischargeMosEnabled)
            .putLong(KEY_BALANCING_MASK, info.balancingMask)
            .putInt(KEY_PROTECTION_MASK, info.protectionMask)
            .putOptionalInt(KEY_ALARM_MASK, info.alarmMask)
            .putString(KEY_SOFTWARE_VERSION, info.softwareVersion)
            .putString(KEY_PRODUCTION_DATE, info.productionDate)
            .putOptionalInt(KEY_HUMIDITY, info.humidityPercent)
            .putOptionalInt(KEY_BALANCING_CURRENT, info.balancingCurrentMa)
            .putLong(KEY_DATA_UPDATED_AT, info.updatedAtMillis)
            .putString(KEY_CELLS, snapshot.cells?.millivolts?.joinToString(","))
            .putLong(KEY_CELLS_UPDATED_AT, snapshot.cells?.updatedAtMillis ?: -1L)
            .putDouble(KEY_GPS_CURRENT, snapshot.gpsSpeed.currentKmh)
            .putDouble(KEY_GPS_AVERAGE, snapshot.gpsSpeed.average5SecondsKmh)
            .putDouble(KEY_GPS_MAXIMUM, snapshot.gpsSpeed.maximumKmh)
            .putOptionalLong(KEY_TRIP_STARTED_AT, trip.startedAtMillis)
            .putDouble(KEY_TRIP_DISTANCE, trip.distanceMeters)
            .putOptionalInt(KEY_TRIP_START_SOC, trip.startSocPercent)
            .putOptionalInt(KEY_TRIP_CURRENT_SOC, trip.currentSocPercent)
            .putOptionalDouble(KEY_TRIP_START_AH, trip.startRemainingAh)
            .putOptionalDouble(KEY_TRIP_CURRENT_AH, trip.currentRemainingAh)
            .putDouble(KEY_TRIP_CONSUMED_AH, trip.integratedConsumedAh)
            .putDouble(KEY_TRIP_CONSUMED_WH, trip.integratedConsumedWh)
            .putDouble(KEY_TRIP_CURRENT, trip.currentA)
            .putOptionalFloat(KEY_TRIP_ACCURACY, trip.locationAccuracyMeters)
            .putInt(KEY_TRIP_POINTS, trip.validLocationPoints)
            .putOptionalLong(KEY_TRIP_LAST_LOCATION, trip.lastLocationAtMillis)
            .commit()
    }

    private fun SharedPreferences.double(key: String): Double? = getString(key, null)?.toDoubleOrNull()
    private fun String?.toDoubleList(): List<Double> = this?.split(',')?.mapNotNull(String::toDoubleOrNull).orEmpty()
    private fun SharedPreferences.optionalInt(key: String): Int? = if (contains(key)) getInt(key, 0) else null
    private fun SharedPreferences.optionalLong(key: String): Long? = if (contains(key)) getLong(key, 0L) else null
    private fun SharedPreferences.optionalFloat(key: String): Float? = if (contains(key)) getFloat(key, 0f) else null
    private fun SharedPreferences.Editor.putDouble(key: String, value: Double) = putString(key, value.toString())
    private fun SharedPreferences.Editor.putOptionalDouble(key: String, value: Double?) =
        value?.let { putDouble(key, it) } ?: remove(key)
    private fun SharedPreferences.Editor.putOptionalInt(key: String, value: Int?) =
        value?.let { putInt(key, it) } ?: remove(key)
    private fun SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) =
        value?.let { putLong(key, it) } ?: remove(key)
    private fun SharedPreferences.Editor.putOptionalFloat(key: String, value: Float?) =
        value?.let { putFloat(key, it) } ?: remove(key)

    private companion object {
        const val PREFERENCES_NAME = "jbd_last_snapshot"
        const val KEY_EXISTS = "exists"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_CHIP_TYPE = "chip_type"
        const val KEY_PROTOCOL_PROFILE = "protocol_profile"
        const val KEY_DETECTED_PROTOCOL = "detected_protocol"
        const val KEY_TOTAL_VOLTAGE = "total_voltage"
        const val KEY_CURRENT = "current"
        const val KEY_REMAINING_AH = "remaining_ah"
        const val KEY_NOMINAL_AH = "nominal_ah"
        const val KEY_FULL_CHARGE_AH = "full_charge_ah"
        const val KEY_SOC = "soc"
        const val KEY_CYCLES = "cycles"
        const val KEY_TEMPERATURES = "temperatures"
        const val KEY_CELL_COUNT = "cell_count"
        const val KEY_CHARGE_MOS = "charge_mos"
        const val KEY_DISCHARGE_MOS = "discharge_mos"
        const val KEY_BALANCING_MASK = "balancing_mask"
        const val KEY_PROTECTION_MASK = "protection_mask"
        const val KEY_ALARM_MASK = "alarm_mask"
        const val KEY_SOFTWARE_VERSION = "software_version"
        const val KEY_PRODUCTION_DATE = "production_date"
        const val KEY_HUMIDITY = "humidity"
        const val KEY_BALANCING_CURRENT = "balancing_current"
        const val KEY_DATA_UPDATED_AT = "data_updated_at"
        const val KEY_CELLS = "cells"
        const val KEY_CELLS_UPDATED_AT = "cells_updated_at"
        const val KEY_GPS_CURRENT = "gps_current"
        const val KEY_GPS_AVERAGE = "gps_average"
        const val KEY_GPS_MAXIMUM = "gps_maximum"
        const val KEY_TRIP_STARTED_AT = "trip_started_at"
        const val KEY_TRIP_DISTANCE = "trip_distance"
        const val KEY_TRIP_START_SOC = "trip_start_soc"
        const val KEY_TRIP_CURRENT_SOC = "trip_current_soc"
        const val KEY_TRIP_START_AH = "trip_start_ah"
        const val KEY_TRIP_CURRENT_AH = "trip_current_ah"
        const val KEY_TRIP_CONSUMED_AH = "trip_consumed_ah"
        const val KEY_TRIP_CONSUMED_WH = "trip_consumed_wh"
        const val KEY_TRIP_CURRENT = "trip_current"
        const val KEY_TRIP_ACCURACY = "trip_accuracy"
        const val KEY_TRIP_POINTS = "trip_points"
        const val KEY_TRIP_LAST_LOCATION = "trip_last_location"
    }
}
