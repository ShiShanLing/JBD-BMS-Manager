package com.bms.jbdmanager.storage

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.model.TripSessionRecord
import org.json.JSONArray
import org.json.JSONObject

internal class MileageHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSessions(): List<TripSessionRecord> {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TripSessionRecord(
                            startedAtMillis = item.getLong(KEY_STARTED),
                            finishedAtMillis = item.getLong(KEY_FINISHED),
                            distanceMeters = item.getDouble(KEY_DISTANCE),
                            consumedAh = item.optDouble(KEY_CONSUMED_AH, 0.0),
                            consumedWh = item.optDouble(KEY_CONSUMED_WH, 0.0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun archiveTrip(
        startedAtMillis: Long,
        finishedAtMillis: Long,
        distanceMeters: Double,
        consumedAh: Double,
        consumedWh: Double
    ): List<TripSessionRecord> {
        if (distanceMeters < 10.0) return loadSessions()
        val sessions = loadSessions().toMutableList()
        sessions += TripSessionRecord(
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
            distanceMeters = distanceMeters,
            consumedAh = consumedAh,
            consumedWh = consumedWh
        )
        val trimmed = sessions
            .sortedByDescending { it.startedAtMillis }
            .take(MAX_SESSIONS)
        write(trimmed)
        return trimmed
    }

    fun clearAll() {
        preferences.edit().remove(KEY_SESSIONS).apply()
    }

    private fun write(sessions: List<TripSessionRecord>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put(KEY_STARTED, session.startedAtMillis)
                    .put(KEY_FINISHED, session.finishedAtMillis)
                    .put(KEY_DISTANCE, session.distanceMeters)
                    .put(KEY_CONSUMED_AH, session.consumedAh)
                    .put(KEY_CONSUMED_WH, session.consumedWh)
            )
        }
        preferences.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "jbd_mileage_history"
        const val KEY_SESSIONS = "sessions"
        const val KEY_STARTED = "started_at"
        const val KEY_FINISHED = "finished_at"
        const val KEY_DISTANCE = "distance_meters"
        const val KEY_CONSUMED_AH = "consumed_ah"
        const val KEY_CONSUMED_WH = "consumed_wh"
        const val MAX_SESSIONS = 500
    }
}
