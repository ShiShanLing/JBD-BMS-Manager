package com.bms.jbdmanager.storage

import android.content.Context
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.CapacityHealthRecordSource
import org.json.JSONArray
import org.json.JSONObject

//MARK:容量健康存储
//CapacityHealthStore 使用 SharedPreferences 持久化容量测试记录，并统一负责记录的解析、排序、去重和数量限制。
internal class CapacityHealthStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    //MARK:读取记录
    //读取本地 JSON 数组并还原为容量健康记录；没有数据或整体解析失败时返回空列表，避免损坏内容导致页面崩溃。
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
            //无论文件中的原始顺序如何，始终把最新一次容量测试排在最前面。
            }.sortedByDescending { it.recordedAtMillis }
        }.getOrDefault(emptyList())
    }

    //MARK:添加记录
    //添加或更新一条容量记录；ID 相同视为覆盖，随后按测试时间倒序排列，并只保留最近 MAX_RECORDS 条。
    fun add(record: CapacityHealthRecord): List<CapacityHealthRecord> {
        val updated = (load().filterNot { it.id == record.id } + record)
            .sortedByDescending { it.recordedAtMillis }
            .take(MAX_RECORDS)
        write(updated)
        return updated
    }

    //MARK:删除记录
    //删除指定 ID 的容量记录并立即覆盖本地数据；ID 不存在时列表内容保持不变。
    fun delete(id: Long): List<CapacityHealthRecord> {
        val updated = load().filterNot { it.id == id }
        write(updated)
        return updated
    }

    //MARK:写入数据
    //把完整记录列表编码成 JSON 后一次性写入；可选字段为空时不写键，以兼容早期没有这些字段的数据。
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

    //MARK:可选小数
    //读取可选的小数字段；键不存在或值为 JSON null 时返回 Kotlin null。
    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    //MARK:可选整数
    //读取可选的整数字段；用于兼容旧版本未保存循环次数等字段的记录。
    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    //MARK:常量配置
    //集中声明存储文件名、JSON 数据键和记录上限，修改键名时需同时考虑旧版本数据迁移。
    private companion object {
        const val PREFERENCES_NAME = "jbd_capacity_health"
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 200
    }
}
