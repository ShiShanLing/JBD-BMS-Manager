package com.bms.jbdmanager.storage

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.BuildConfig
import com.bms.jbdmanager.model.BackupRestorePreview
import com.bms.jbdmanager.model.DataExportSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class PreparedDataRestore(
    val directory: File,
    val preview: BackupRestorePreview
)

private sealed interface PreferenceValue {
    data class StringValue(val value: String) : PreferenceValue
    data class IntValue(val value: Int) : PreferenceValue
    data class LongValue(val value: Long) : PreferenceValue
    data class FloatValue(val value: Float) : PreferenceValue
    data class BooleanValue(val value: Boolean) : PreferenceValue
    data class StringSetValue(val value: Set<String>) : PreferenceValue
}

private typealias PreferenceSnapshot = Map<String, Map<String, PreferenceValue>>

internal class DataArchiveManager(
    context: Context,
    private val trendStore: BatteryTrendStore
) {
    private val appContext = context.applicationContext

    fun createFullBackup(output: OutputStream) {
        val temporary = newTemporaryDirectory("backup")
        try {
            val databaseSnapshot = File(temporary, DATABASE_ENTRY)
            trendStore.exportDatabaseSnapshot(databaseSnapshot)
            val stats = trendStore.validateDatabaseSnapshot(databaseSnapshot)
            val createdAt = System.currentTimeMillis()
            val preferences = encodePreferences(capturePreferences(allPreferenceNames()))
            val manifest = JSONObject()
                .put("formatVersion", BACKUP_FORMAT_VERSION)
                .put("packageName", appContext.packageName)
                .put("createdAtMillis", createdAt)
                .put("appVersionName", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("trendSampleCount", stats.sampleCount)
                .put("dailySummaryCount", stats.dailySummaryCount)
                .put("fullChargeFingerprintCount", stats.fullChargeFingerprintCount)
                .put("preferenceGroupCount", preferences.getJSONArray("groups").length())
            ZipOutputStream(output.buffered()).use { zip ->
                zip.writeTextEntry(MANIFEST_ENTRY, manifest.toString(2))
                zip.writeTextEntry(PREFERENCES_ENTRY, preferences.toString())
                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                databaseSnapshot.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    fun prepareRestore(input: InputStream): PreparedDataRestore {
        clearStaleRestoreDirectories()
        val directory = newTemporaryDirectory("restore")
        try {
            val extracted = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in ALLOWED_BACKUP_ENTRIES) { "备份包含未知文件" }
                    require(extracted.add(entry.name)) { "备份包含重复文件" }
                    val maximum = when (entry.name) {
                        DATABASE_ENTRY -> MAX_DATABASE_BYTES
                        PREFERENCES_ENTRY -> MAX_PREFERENCES_BYTES
                        else -> MAX_MANIFEST_BYTES
                    }
                    File(directory, entry.name).outputStream().buffered().use { output ->
                        copyLimited(zip, output, maximum)
                    }
                    zip.closeEntry()
                }
            }
            require(extracted == ALLOWED_BACKUP_ENTRIES) { "备份文件不完整" }
            val manifest = JSONObject(File(directory, MANIFEST_ENTRY).readText())
            require(manifest.getInt("formatVersion") == BACKUP_FORMAT_VERSION) { "不支持此备份格式" }
            require(manifest.getString("packageName") == appContext.packageName) { "这不是电动BMS的备份文件" }
            val preferenceSnapshot = decodePreferences(JSONObject(File(directory, PREFERENCES_ENTRY).readText()))
            val stats = trendStore.validateDatabaseSnapshot(File(directory, DATABASE_ENTRY))
            val preview = BackupRestorePreview(
                createdAtMillis = manifest.getLong("createdAtMillis"),
                sourceVersionName = manifest.optString("appVersionName", "未知"),
                trendSampleCount = stats.sampleCount,
                dailySummaryCount = stats.dailySummaryCount,
                fullChargeFingerprintCount = stats.fullChargeFingerprintCount,
                preferenceGroupCount = preferenceSnapshot.size
            )
            return PreparedDataRestore(directory, preview)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun restore(prepared: PreparedDataRestore) {
        require(prepared.directory.parentFile == restoreRoot()) { "恢复文件位置无效" }
        val preferencesFile = File(prepared.directory, PREFERENCES_ENTRY)
        val databaseFile = File(prepared.directory, DATABASE_ENTRY)
        val replacement = decodePreferences(JSONObject(preferencesFile.readText()))
        trendStore.validateDatabaseSnapshot(databaseFile)
        val clearNames = KNOWN_PREFERENCE_NAMES + replacement.keys
        val original = capturePreferences(clearNames)
        try {
            applyPreferences(replacement, clearNames)
            trendStore.replaceFromDatabaseSnapshot(databaseFile)
        } catch (error: Throwable) {
            runCatching { applyPreferences(original, clearNames) }
            throw error
        } finally {
            prepared.directory.deleteRecursively()
        }
    }

    fun cancelRestore(prepared: PreparedDataRestore?) {
        prepared?.directory?.takeIf { it.parentFile == restoreRoot() }?.deleteRecursively()
    }

    fun exportCsvPackage(output: OutputStream, snapshot: DataExportSnapshot) {
        ZipOutputStream(output.buffered()).use { zip ->
            trendStore.writeCsvEntries(zip)
            zip.writeCsvEntry(
                "容量测试.csv",
                "时间,时间戳,实测容量Ah,BMS总容量Ah,SOH%,实测电量Wh,循环次数,平均温度C,来源,正式记录,数据覆盖率%,备注",
                snapshot.capacityRecords.sortedBy { it.recordedAtMillis }.map { record ->
                    listOf(
                        csvTime(record.recordedAtMillis), record.recordedAtMillis,
                        record.measuredDischargeAh, record.ratedCapacityAh, record.sohPercent,
                        record.measuredDischargeWh, record.cycleCount, record.averageTemperatureC,
                        record.source.name, record.qualifiedForHealth, record.qualityPercent, record.note
                    )
                }
            )
            zip.writeCsvEntry(
                "保护告警.csv",
                "开始时间,开始时间戳,解除时间戳,名称,等级,说明,SOC%,总压V,电流A,最低单体mV,最高单体mV,压差mV,最高温度C,设备地址",
                snapshot.protectionEvents.sortedBy { it.startedAtMillis }.map { event ->
                    listOf(
                        csvTime(event.startedAtMillis), event.startedAtMillis, event.resolvedAtMillis,
                        event.title, event.severity.name, event.summary, event.stateOfChargePercent,
                        event.totalVoltageV, event.currentA, event.minimumCellMv, event.maximumCellMv,
                        event.cellDeltaMv, event.maximumTemperatureC, event.deviceAddress
                    )
                }
            )
            zip.writeCsvEntry(
                "骑行记录.csv",
                "开始时间,开始时间戳,结束时间戳,里程km,消耗Ah,消耗Wh",
                snapshot.mileageSessions.sortedBy { it.startedAtMillis }.map { session ->
                    listOf(
                        csvTime(session.startedAtMillis), session.startedAtMillis, session.finishedAtMillis,
                        session.distanceMeters / 1_000.0, session.consumedAh, session.consumedWh
                    )
                }
            )
            zip.writeCsvEntry(
                "分速度续航样本.csv",
                "目标速度kmh,有效里程km,有效时长s,消耗Ah,消耗Wh,Ah每100km,Wh每km",
                snapshot.tripState.speedRangeStats.map { stats ->
                    listOf(
                        stats.targetSpeedKmh, stats.effectiveDistanceKm, stats.effectiveDurationSeconds,
                        stats.consumedAh, stats.consumedWh, stats.ahPer100Km, stats.whPerKm
                    )
                }
            )
            zip.writeTextEntry(
                "说明.txt",
                "导出时间：${csvTime(System.currentTimeMillis())}\n" +
                    "数据来自电动BMS本地只读记录。单体电压变化受SOC、温度和充电截止条件影响，不能单独作为容量结论。\n"
            )
        }
    }

    private fun allPreferenceNames(): Set<String> {
        val directory = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val discovered = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
        return KNOWN_PREFERENCE_NAMES + discovered
    }

    private fun capturePreferences(names: Set<String>): PreferenceSnapshot = names.associateWith { name ->
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
            value.toPreferenceValue()
        }
    }

    private fun applyPreferences(snapshot: PreferenceSnapshot, clearNames: Set<String>) {
        clearNames.forEach { name ->
            require(PREFERENCE_NAME_PATTERN.matches(name)) { "备份中的配置名称无效" }
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            snapshot[name].orEmpty().forEach { (key, value) -> editor.putPreferenceValue(key, value) }
            require(editor.commit()) { "写入配置失败：$name" }
        }
    }

    private fun encodePreferences(snapshot: PreferenceSnapshot): JSONObject = JSONObject().apply {
        val groups = JSONArray()
        snapshot.toSortedMap().forEach { (name, values) ->
            val entries = JSONArray()
            values.toSortedMap().forEach { (key, value) ->
                entries.put(value.toJson(key))
            }
            groups.put(JSONObject().put("name", name).put("entries", entries))
        }
        put("groups", groups)
    }

    private fun decodePreferences(json: JSONObject): PreferenceSnapshot {
        val groups = json.getJSONArray("groups")
        require(groups.length() <= 100) { "备份配置数量异常" }
        return buildMap {
            for (groupIndex in 0 until groups.length()) {
                val group = groups.getJSONObject(groupIndex)
                val name = group.getString("name")
                require(PREFERENCE_NAME_PATTERN.matches(name)) { "备份中的配置名称无效" }
                require(!containsKey(name)) { "备份包含重复配置" }
                val entries = group.getJSONArray("entries")
                require(entries.length() <= 20_000) { "备份配置内容异常" }
                put(name, buildMap {
                    for (entryIndex in 0 until entries.length()) {
                        val entry = entries.getJSONObject(entryIndex)
                        val key = entry.getString("key")
                        require(key.length in 1..500 && !containsKey(key)) { "备份配置键无效" }
                        put(key, entry.toPreferenceValue())
                    }
                })
            }
        }
    }

    private fun newTemporaryDirectory(prefix: String): File {
        val root = if (prefix == "restore") restoreRoot() else File(appContext.cacheDir, "data-archives")
        root.mkdirs()
        return File(root, "$prefix-${UUID.randomUUID()}").also { require(it.mkdirs()) }
    }

    private fun restoreRoot(): File = File(appContext.cacheDir, "pending-restores").apply { mkdirs() }

    private fun clearStaleRestoreDirectories() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1_000L
        restoreRoot().listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, maximumBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximumBytes) { "备份文件过大" }
            output.write(buffer, 0, count)
        }
    }

    private fun Any?.toPreferenceValue(): PreferenceValue = when (this) {
        is String -> PreferenceValue.StringValue(this)
        is Int -> PreferenceValue.IntValue(this)
        is Long -> PreferenceValue.LongValue(this)
        is Float -> PreferenceValue.FloatValue(this)
        is Boolean -> PreferenceValue.BooleanValue(this)
        is Set<*> -> PreferenceValue.StringSetValue(map {
            require(it is String) { "配置集合中包含未知类型" }
            it
        }.toSet())
        else -> error("不支持的配置类型")
    }

    private fun PreferenceValue.toJson(key: String): JSONObject = JSONObject().put("key", key).apply {
        when (this@toJson) {
            is PreferenceValue.StringValue -> put("type", "string").put("value", value)
            is PreferenceValue.IntValue -> put("type", "int").put("value", value)
            is PreferenceValue.LongValue -> put("type", "long").put("value", value)
            is PreferenceValue.FloatValue -> put("type", "float").put("value", value.toDouble())
            is PreferenceValue.BooleanValue -> put("type", "boolean").put("value", value)
            is PreferenceValue.StringSetValue -> put("type", "stringSet").put("value", JSONArray(value.toList().sorted()))
        }
    }

    private fun JSONObject.toPreferenceValue(): PreferenceValue = when (getString("type")) {
        "string" -> PreferenceValue.StringValue(getString("value"))
        "int" -> PreferenceValue.IntValue(getInt("value"))
        "long" -> PreferenceValue.LongValue(getLong("value"))
        "float" -> PreferenceValue.FloatValue(getDouble("value").toFloat())
        "boolean" -> PreferenceValue.BooleanValue(getBoolean("value"))
        "stringSet" -> PreferenceValue.StringSetValue(getJSONArray("value").let { array ->
            buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
        })
        else -> error("备份包含未知配置类型")
    }

    private fun SharedPreferences.Editor.putPreferenceValue(key: String, value: PreferenceValue) {
        when (value) {
            is PreferenceValue.StringValue -> putString(key, value.value)
            is PreferenceValue.IntValue -> putInt(key, value.value)
            is PreferenceValue.LongValue -> putLong(key, value.value)
            is PreferenceValue.FloatValue -> putFloat(key, value.value)
            is PreferenceValue.BooleanValue -> putBoolean(key, value.value)
            is PreferenceValue.StringSetValue -> putStringSet(key, value.value)
        }
    }

    private fun ZipOutputStream.writeTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeCsvEntry(name: String, header: String, rows: List<List<Any?>>) {
        putNextEntry(ZipEntry(name))
        write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        write((header + "\n").toByteArray(StandardCharsets.UTF_8))
        rows.forEach { row ->
            write((row.joinToString(",") { csvValue(it) } + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        closeEntry()
    }

    private fun csvValue(value: Any?): String {
        if (value == null) return ""
        val text = when (value) {
            is Double -> String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
            else -> value.toString()
        }
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${text.replace("\"", "\"\"")}\""
        } else text
    }

    private fun csvTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()

    companion object {
        private const val BACKUP_FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val PREFERENCES_ENTRY = "preferences.json"
        private const val DATABASE_ENTRY = "battery_trends.db"
        private val ALLOWED_BACKUP_ENTRIES = setOf(MANIFEST_ENTRY, PREFERENCES_ENTRY, DATABASE_ENTRY)
        private const val MAX_MANIFEST_BYTES = 256 * 1_024L
        private const val MAX_PREFERENCES_BYTES = 20 * 1_024 * 1_024L
        private const val MAX_DATABASE_BYTES = 512 * 1_024 * 1_024L
        private val PREFERENCE_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
        private val KNOWN_PREFERENCE_NAMES = setOf(
            "jbd_bms_preferences",
            "jbd_last_snapshot",
            "jbd_trip_tracking",
            "jbd_mileage_history",
            "jbd_capacity_health",
            "jbd_automatic_capacity_test",
            "jbd_protection_events",
            "jbd_app_update"
        )
    }
}
