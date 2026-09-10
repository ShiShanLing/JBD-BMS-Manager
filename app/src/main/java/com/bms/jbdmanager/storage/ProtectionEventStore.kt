package com.bms.jbdmanager.storage

import android.content.Context
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.ProtectionEventSeverity
import org.json.JSONArray
import org.json.JSONObject

//MARK:保护事件存储
//ProtectionEventStore 封装本地持久化、兼容解析和写回规则，用于处理保护事件。
internal class ProtectionEventStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    //MARK:读取记录
    //解析保护事件 JSON，恢复触发与解除时间、严重程度及当时电池证据，并按最新事件倒序返回。
    fun load(): List<ProtectionEvent> {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ProtectionEvent(
                            id = item.getLong("id"),
                            protectionBit = item.getInt("protectionBit"),
                            title = item.getString("title"),
                            startedAtMillis = item.getLong("startedAtMillis"),
                            resolvedAtMillis = item.optionalLong("resolvedAtMillis"),
                            severity = runCatching {
                                ProtectionEventSeverity.valueOf(item.getString("severity"))
                            }.getOrDefault(ProtectionEventSeverity.Critical),
                            summary = item.optString("summary"),
                            stateOfChargePercent = item.getInt("stateOfChargePercent"),
                            totalVoltageV = item.getDouble("totalVoltageV"),
                            currentA = item.getDouble("currentA"),
                            minimumCellMv = item.optionalInt("minimumCellMv"),
                            maximumCellMv = item.optionalInt("maximumCellMv"),
                            cellDeltaMv = item.optionalInt("cellDeltaMv"),
                            maximumTemperatureC = item.optionalDouble("maximumTemperatureC"),
                            deviceAddress = item.optionalString("deviceAddress"),
                            deviceName = item.optionalString("deviceName")
                        )
                    )
                }
            }.sortedByDescending { it.startedAtMillis }
        }.getOrDefault(emptyList())
    }

    //MARK:替换记录
    //replace 用传入的完整集合替换本地记录，并在写入前执行排序、去重和数量限制。
    fun replace(events: List<ProtectionEvent>): List<ProtectionEvent> {
        val retained = events.sortedByDescending { it.startedAtMillis }.take(MAX_EVENTS)
        val array = JSONArray().apply {
            retained.forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id)
                    put("protectionBit", event.protectionBit)
                    put("title", event.title)
                    put("startedAtMillis", event.startedAtMillis)
                    event.resolvedAtMillis?.let { put("resolvedAtMillis", it) }
                    put("severity", event.severity.name)
                    put("summary", event.summary)
                    put("stateOfChargePercent", event.stateOfChargePercent)
                    put("totalVoltageV", event.totalVoltageV)
                    put("currentA", event.currentA)
                    event.minimumCellMv?.let { put("minimumCellMv", it) }
                    event.maximumCellMv?.let { put("maximumCellMv", it) }
                    event.cellDeltaMv?.let { put("cellDeltaMv", it) }
                    event.maximumTemperatureC?.let { put("maximumTemperatureC", it) }
                    event.deviceAddress?.let { put("deviceAddress", it) }
                    event.deviceName?.let { put("deviceName", it) }
                })
            }
        }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
        return retained
    }

    //MARK:可选长整
    //optionalLong 读取可选长整数字段；键缺失或值为 JSON null 时返回 Kotlin null。
    private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    //MARK:可选整数
    //optionalInt 读取可选整数字段；键缺失或值为 JSON null 时返回 Kotlin null。
    private fun JSONObject.optionalInt(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    //MARK:可选小数
    //optionalDouble 读取可选小数字段；键缺失或值为 JSON null 时返回 Kotlin null。
    private fun JSONObject.optionalDouble(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    //MARK:可选文本
    //optionalString 读取可选字符串字段；键缺失或 JSON null 时返回 Kotlin null。
    private fun JSONObject.optionalString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    //MARK:常量配置
    //声明保护事件列表存储键和最多 500 条限制，避免长期告警历史无限占用空间。
    private companion object {
        const val PREFERENCES_NAME = "jbd_protection_events"
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 500
    }
}
