package com.bms.jbdmanager.storage

import android.content.Context
import com.bms.jbdmanager.model.AutomaticCapacityTestPhase
import com.bms.jbdmanager.model.AutomaticCapacityTestState
import org.json.JSONObject

internal class AutomaticCapacityTestStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AutomaticCapacityTestState {
        val raw = preferences.getString(KEY_STATE, null) ?: return AutomaticCapacityTestState()
        return runCatching {
            val item = JSONObject(raw)
            AutomaticCapacityTestState(
                phase = enumValueOf(item.optString("phase", AutomaticCapacityTestPhase.Idle.name)),
                autoStartSuppressed = item.optBoolean("autoStartSuppressed"),
                startedAutomatically = item.optBoolean("startedAutomatically"),
                deviceAddress = item.optString("deviceAddress").takeIf { it.isNotBlank() },
                startedAtMillis = item.optionalLong("startedAtMillis"),
                finishedAtMillis = item.optionalLong("finishedAtMillis"),
                startSocPercent = item.optionalInt("startSocPercent"),
                currentSocPercent = item.optionalInt("currentSocPercent"),
                startRemainingAh = item.optionalDouble("startRemainingAh"),
                currentRemainingAh = item.optionalDouble("currentRemainingAh"),
                ratedCapacityAh = item.optionalDouble("ratedCapacityAh"),
                dischargedAh = item.optDouble("dischargedAh", 0.0),
                dischargedWh = item.optDouble("dischargedWh", 0.0),
                missingDischargeAh = item.optDouble("missingDischargeAh", 0.0),
                chargingInterruptionAh = item.optDouble("chargingInterruptionAh", 0.0),
                validDurationSeconds = item.optDouble("validDurationSeconds", 0.0),
                sampleCount = item.optInt("sampleCount", 0),
                temperatureSumC = item.optDouble("temperatureSumC", 0.0),
                temperatureSampleCount = item.optInt("temperatureSampleCount", 0),
                lowVoltageReached = item.optBoolean("lowVoltageReached"),
                finishReason = item.optString("finishReason").takeIf { it.isNotBlank() },
                lastSampleAtMillis = item.optionalLong("lastSampleAtMillis"),
                lastCurrentA = item.optionalDouble("lastCurrentA"),
                lastVoltageV = item.optionalDouble("lastVoltageV"),
                lastRemainingAh = item.optionalDouble("lastRemainingAh")
            )
        }.getOrDefault(AutomaticCapacityTestState())
    }

    fun save(state: AutomaticCapacityTestState) {
        val item = JSONObject().apply {
            put("phase", state.phase.name)
            put("autoStartSuppressed", state.autoStartSuppressed)
            put("startedAutomatically", state.startedAutomatically)
            state.deviceAddress?.let { put("deviceAddress", it) }
            state.startedAtMillis?.let { put("startedAtMillis", it) }
            state.finishedAtMillis?.let { put("finishedAtMillis", it) }
            state.startSocPercent?.let { put("startSocPercent", it) }
            state.currentSocPercent?.let { put("currentSocPercent", it) }
            state.startRemainingAh?.let { put("startRemainingAh", it) }
            state.currentRemainingAh?.let { put("currentRemainingAh", it) }
            state.ratedCapacityAh?.let { put("ratedCapacityAh", it) }
            put("dischargedAh", state.dischargedAh)
            put("dischargedWh", state.dischargedWh)
            put("missingDischargeAh", state.missingDischargeAh)
            put("chargingInterruptionAh", state.chargingInterruptionAh)
            put("validDurationSeconds", state.validDurationSeconds)
            put("sampleCount", state.sampleCount)
            put("temperatureSumC", state.temperatureSumC)
            put("temperatureSampleCount", state.temperatureSampleCount)
            put("lowVoltageReached", state.lowVoltageReached)
            state.finishReason?.let { put("finishReason", it) }
            state.lastSampleAtMillis?.let { put("lastSampleAtMillis", it) }
            state.lastCurrentA?.let { put("lastCurrentA", it) }
            state.lastVoltageV?.let { put("lastVoltageV", it) }
            state.lastRemainingAh?.let { put("lastRemainingAh", it) }
        }
        preferences.edit().putString(KEY_STATE, item.toString()).apply()
    }

    fun clear() = preferences.edit().remove(KEY_STATE).apply()

    private fun JSONObject.optionalDouble(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.optionalInt(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    private companion object {
        const val PREFERENCES_NAME = "jbd_automatic_capacity_test"
        const val KEY_STATE = "state"
    }
}
