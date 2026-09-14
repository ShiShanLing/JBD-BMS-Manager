package com.bms.jbdmanager.storage

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.model.TripSessionRecord
import com.bms.jbdmanager.model.RegenerationPeak
import com.bms.jbdmanager.model.TripCategory
import org.json.JSONArray
import org.json.JSONObject

//MARK:里程历史存储
//MileageHistoryStore 封装本地持久化、兼容解析和写回规则，用于处理里程历史。
internal class MileageHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    //MARK:读取行程会话
    //loadSessions 读取并解析全部历史行程会话；没有记录或 JSON 损坏时返回空列表。
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
                            consumedWh = item.optDouble(KEY_CONSUMED_WH, 0.0),
                            maximumRegeneration = item.optJSONObject(KEY_MAXIMUM_REGENERATION)?.let { peak ->
                                RegenerationPeak(
                                    currentA = peak.optDouble(KEY_REGEN_CURRENT_A, 0.0),
                                    powerW = peak.optDouble(KEY_REGEN_POWER_W, 0.0),
                                    speedKmh = peak.optDouble(KEY_REGEN_SPEED_KMH, 0.0),
                                    recordedAtMillis = peak.optLong(KEY_REGEN_RECORDED_AT, 0L)
                                )
                            },
                            category = item.optString(KEY_CATEGORY, TripCategory.Electric.name)
                                .let { runCatching { TripCategory.valueOf(it) }.getOrDefault(TripCategory.Electric) },
                            movingDurationSeconds = item.optDouble(KEY_MOVING_DURATION, 0.0),
                            estimatedCaloriesKcal = item.optDouble(KEY_CALORIES, 0.0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    //MARK:归档行程
    //行程超过 10 米才写入历史；新增记录按开始时间倒序排列，并只保留最近 MAX_SESSIONS 条。
    fun archiveTrip(
        startedAtMillis: Long,
        finishedAtMillis: Long,
        distanceMeters: Double,
        consumedAh: Double,
        consumedWh: Double,
        maximumRegeneration: RegenerationPeak? = null,
        category: TripCategory = TripCategory.Electric,
        movingDurationSeconds: Double = 0.0,
        estimatedCaloriesKcal: Double = 0.0
    ): List<TripSessionRecord> {
        if (distanceMeters < 10.0) return loadSessions()
        val sessions = loadSessions().toMutableList()
        sessions += TripSessionRecord(
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
            distanceMeters = distanceMeters,
            consumedAh = consumedAh,
            consumedWh = consumedWh,
            maximumRegeneration = maximumRegeneration,
            category = category,
            movingDurationSeconds = movingDurationSeconds,
            estimatedCaloriesKcal = estimatedCaloriesKcal
        )
        val trimmed = sessions
            .sortedByDescending { it.startedAtMillis }
            .take(MAX_SESSIONS)
        write(trimmed)
        return trimmed
    }

    //MARK:清空全部
    //clearAll 删除该模块的全部历史记录；此操作不会只清除当前页面的内存副本。
    fun clearAll() {
        preferences.edit().remove(KEY_SESSIONS).apply()
    }

    //MARK:写入数据
    //write 把已经整理好的完整数据编码后一次性写入目标存储，避免只更新部分字段造成新旧数据混合。
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
                    .put(KEY_CATEGORY, session.category.name)
                    .put(KEY_MOVING_DURATION, session.movingDurationSeconds)
                    .put(KEY_CALORIES, session.estimatedCaloriesKcal)
                    .apply {
                        session.maximumRegeneration?.let { peak ->
                            put(KEY_MAXIMUM_REGENERATION, JSONObject()
                                .put(KEY_REGEN_CURRENT_A, peak.currentA)
                                .put(KEY_REGEN_POWER_W, peak.powerW)
                                .put(KEY_REGEN_SPEED_KMH, peak.speedKmh)
                                .put(KEY_REGEN_RECORDED_AT, peak.recordedAtMillis))
                        }
                    }
            )
        }
        preferences.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    //MARK:常量配置
    //定义行程 JSON 字段键和最多 500 条会话限制，防止历史文件无限增长。
    private companion object {
        const val PREFERENCES_NAME = "jbd_mileage_history"
        const val KEY_SESSIONS = "sessions"
        const val KEY_STARTED = "started_at"
        const val KEY_FINISHED = "finished_at"
        const val KEY_DISTANCE = "distance_meters"
        const val KEY_CONSUMED_AH = "consumed_ah"
        const val KEY_CONSUMED_WH = "consumed_wh"
        const val KEY_CATEGORY = "category"
        const val KEY_MOVING_DURATION = "moving_duration_seconds"
        const val KEY_CALORIES = "estimated_calories_kcal"
        const val KEY_MAXIMUM_REGENERATION = "maximum_regeneration"
        const val KEY_REGEN_CURRENT_A = "current_a"
        const val KEY_REGEN_POWER_W = "power_w"
        const val KEY_REGEN_SPEED_KMH = "speed_kmh"
        const val KEY_REGEN_RECORDED_AT = "recorded_at"
        const val MAX_SESSIONS = 500
    }
}
