package com.bms.jbdmanager.storage

import android.content.Context
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.CapacityHealthRecordSource
import org.json.JSONArray
import org.json.JSONObject

internal class CapacityHealthStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<CapacityHealthRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        CapacityHealthRecord(
                            id = item.getLong("id"),
                            recordedAtMillis = item.getLong("recordedAtMillis"),
                            measuredDischargeAh = item.getDouble("measuredDischargeAh"),
                            ratedCapacityAh = item.getDouble("ratedCapacityAh"),
                            measuredDischargeWh = item.optionalDouble("measuredDischargeWh"),
                            cycleCount = item.optionalInt("cycleCount"),
                            averageTemperatureC = item.optionalDouble("averageTemperatureC"),
                            note = item.optString("note"),
                            source = runCatching {
                                enumValueOf<CapacityHealthRecordSource>(item.optString("source", CapacityHealthRecordSource.Manual.name))
                            }.getOrDefault(CapacityHealthRecordSource.Manual),
                            qualifiedForHealth = item.optBoolean("qualifiedForHealth", true),
                            qualityPercent = item.optionalDouble("qualityPercent")
                        )
                    )
                }
            }.sortedByDescending { it.recordedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun add(record: CapacityHealthRecord): List<CapacityHealthRecord> {
        val updated = (load().filterNot { it.id == record.id } + record)
            .sortedByDescending { it.recordedAtMillis }
            .take(MAX_RECORDS)
        write(updated)
        return updated
    }

    fun delete(id: Long): List<CapacityHealthRecord> {
        val updated = load().filterNot { it.id == id }
        write(updated)
        return updated
    }

    private fun write(records: List<CapacityHealthRecord>) {
        val array = JSONArray().apply {
            records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("recordedAtMillis", record.recordedAtMillis)
                    put("measuredDischargeAh", record.measuredDischargeAh)
                    put("ratedCapacityAh", record.ratedCapacityAh)
                    record.measuredDischargeWh?.let { put("measuredDischargeWh", it) }
                    record.cycleCount?.let { put("cycleCount", it) }
                    record.averageTemperatureC?.let { put("averageTemperatureC", it) }
                    put("note", record.note)
                    put("source", record.source.name)
                    put("qualifiedForHealth", record.qualifiedForHealth)
                    record.qualityPercent?.let { put("qualityPercent", it) }
                })
            }
        }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private companion object {
        const val PREFERENCES_NAME = "jbd_capacity_health"
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 200
    }
}
