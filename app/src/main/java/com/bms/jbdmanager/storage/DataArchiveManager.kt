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

//MARK:待恢复数据
//PreparedDataRestore 将数据恢复相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
internal data class PreparedDataRestore(
    val directory: File,
    val preview: BackupRestorePreview
)

//MARK:配置值类型
//PreferenceValue 定义偏好数据的封闭结果类型，使调用方能够穷举处理每一种返回情况。
private sealed interface PreferenceValue {
    //MARK:文本配置值
    //StringValue 将值相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class StringValue(val value: String) : PreferenceValue
    //MARK:整数配置值
    //IntValue 将值相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class IntValue(val value: Int) : PreferenceValue
    //MARK:长整配置值
    //LongValue 将值相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class LongValue(val value: Long) : PreferenceValue
    //MARK:浮点配置值
    //FloatValue 将值相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class FloatValue(val value: Float) : PreferenceValue
    //MARK:布尔配置值
    //BooleanValue 将值相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class BooleanValue(val value: Boolean) : PreferenceValue
    //MARK:文本集配置值
    //StringSetValue 将值相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class StringSetValue(val value: Set<String>) : PreferenceValue
}

private typealias PreferenceSnapshot = Map<String, Map<String, PreferenceValue>>

//MARK:数据归档管理
//DataArchiveManager 协调相关资源、状态变化和失败处理，用于处理数据归档。
internal class DataArchiveManager(
    context: Context,
    private val trendStore: BatteryTrendStore
) {
    private val appContext = context.applicationContext

    //MARK:创建备份
    //createFullBackup 生成包含配置、历史数据库和清单信息的完整 ZIP 备份，并在输出前验证数据库快照。
    fun createFullBackup(output: OutputStream) {
        val temporary = newTemporaryDirectory("backup")
        try {
            // 先让 SQLite 生成一致性快照并校验，再写 ZIP，避免直接复制正在写入的数据库产生损坏备份。
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
                .put("fullChargeDeltaCount", stats.fullChargeDeltaCount)
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

    //MARK:准备恢复
    //prepareRestore 把备份解压到受控临时目录，校验文件集合、大小、格式和来源后生成恢复预览。
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
                    // 每类文件都有独立上限，可同时阻止超大备份耗尽空间以及 ZIP 解压膨胀攻击。
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
            // 准备阶段只验证和生成预览，不修改当前数据；用户确认后才执行 restore。
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
                fullChargeDeltaCount = stats.fullChargeDeltaCount,
                preferenceGroupCount = preferenceSnapshot.size
            )
            return PreparedDataRestore(directory, preview)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    //MARK:恢复数据
    //restore 在用户确认后替换偏好数据和趋势数据库；中途失败时尽量回滚原有配置。
    fun restore(prepared: PreparedDataRestore) {
        // 只接受本管理器创建的待恢复目录，防止调用方把任意文件路径作为数据库替换源。
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
            // 数据库替换失败时至少恢复原偏好数据，避免出现“新配置 + 旧数据库”的半恢复状态。
            runCatching { applyPreferences(original, clearNames) }
            throw error
        } finally {
            prepared.directory.deleteRecursively()
        }
    }

    //MARK:取消恢复
    //cancelRestore 删除尚未执行的临时恢复目录，不改变 App 当前保存的任何业务数据。
    fun cancelRestore(prepared: PreparedDataRestore?) {
        prepared?.directory?.takeIf { it.parentFile == restoreRoot() }?.deleteRecursively()
    }

    //MARK:导出数据
    //exportCsvPackage 把趋势、容量、告警和骑行记录分别转换成 CSV，并合并输出为一个 ZIP 文件。
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
                "开始时间,开始时间戳,结束时间戳,里程km,消耗Ah,消耗Wh,最大回收电流A,最大回收功率W,峰值车速kmh,峰值时间戳",
                snapshot.mileageSessions.sortedBy { it.startedAtMillis }.map { session ->
                    listOf(
                        csvTime(session.startedAtMillis), session.startedAtMillis, session.finishedAtMillis,
                        session.distanceMeters / 1_000.0, session.consumedAh, session.consumedWh,
                        session.maximumRegeneration?.currentA, session.maximumRegeneration?.powerW,
                        session.maximumRegeneration?.speedKmh, session.maximumRegeneration?.recordedAtMillis
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

    //MARK:列出配置文件
    //allPreferenceNames 合并已知配置名与 shared_prefs 目录中实际存在的文件名，确保备份不遗漏新增配置。
    private fun allPreferenceNames(): Set<String> {
        val directory = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val discovered = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
        return KNOWN_PREFERENCE_NAMES + discovered
    }

    //MARK:读取配置
    //capturePreferences 读取指定 SharedPreferences 文件的全部键值，并保留原始数据类型用于完整恢复。
    private fun capturePreferences(names: Set<String>): PreferenceSnapshot = names.associateWith { name ->
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
            value.toPreferenceValue()
        }
    }

    //MARK:写入配置
    //applyPreferences 先清空恢复范围内的偏好文件，再按原始类型批量写入备份中的键值。
    private fun applyPreferences(snapshot: PreferenceSnapshot, clearNames: Set<String>) {
        clearNames.forEach { name ->
            require(PREFERENCE_NAME_PATTERN.matches(name)) { "备份中的配置名称无效" }
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            snapshot[name].orEmpty().forEach { (key, value) -> editor.putPreferenceValue(key, value) }
            require(editor.commit()) { "写入配置失败：$name" }
        }
    }

    //MARK:编码配置
    //encodePreferences 把多个偏好文件及其不同类型的键值编码成带类型标记的 JSON。
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

    //MARK:解码配置
    //decodePreferences 校验偏好备份的分组结构和类型标记，并还原为可写入的内存快照。
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

    //MARK:创建临时目录
    //newTemporaryDirectory 在受控缓存根目录中创建本次操作专用的唯一临时目录。
    private fun newTemporaryDirectory(prefix: String): File {
        val root = if (prefix == "restore") restoreRoot() else File(appContext.cacheDir, "data-archives")
        root.mkdirs()
        return File(root, "$prefix-${UUID.randomUUID()}").also { require(it.mkdirs()) }
    }

    //MARK:恢复暂存目录
    //restoreRoot 取得待恢复文件专用缓存目录并确保目录存在，恢复操作只能使用该目录的直接子项。
    private fun restoreRoot(): File = File(appContext.cacheDir, "pending-restores").apply { mkdirs() }

    //MARK:清空恢复
    //clearStaleRestoreDirectories 清理恢复对应的本地内容，同时保留不在本次操作范围内的其他数据。
    private fun clearStaleRestoreDirectories() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1_000L
        restoreRoot().listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }

    //MARK:限量复制
    //copyLimited 分块复制输入流并累计字节数，超过该文件允许的大小时立即终止恢复。
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

    //MARK:转换配置值
    //把 SharedPreferences 返回的运行时值封装成带明确类型的备份值；遇到系统不支持的类型立即拒绝备份。
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

    //MARK:编码JSON
    //将一种带类型的偏好值写成包含键名、类型和值的 JSON 项，保证恢复时类型不丢失。
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

    //MARK:转换配置值
    //根据 JSON 中的类型标记还原字符串、数值、布尔值或字符串集合；未知类型视为损坏备份。
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

    //MARK:写入配置值
    //putPreferenceValue 根据备份值携带的类型写入对应 SharedPreferences API，避免数值或集合类型丢失。
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

    //MARK:写文本条目
    //writeTextEntry 向 ZIP 新建指定名称的文本条目，以 UTF-8 写入内容后正确关闭当前条目。
    private fun ZipOutputStream.writeTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    //MARK:写CSV条目
    //writeCsvEntry 创建一个 CSV 条目，依次写入表头和已经完成转义的每一行数据。
    private fun ZipOutputStream.writeCsvEntry(name: String, header: String, rows: List<List<Any?>>) {
        putNextEntry(ZipEntry(name))
        write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        write((header + "\n").toByteArray(StandardCharsets.UTF_8))
        rows.forEach { row ->
            write((row.joinToString(",") { csvValue(it) } + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        closeEntry()
    }

    //MARK:转义CSV
    //csvValue 把任意字段转换为 CSV 单元格；包含引号、逗号或换行时使用双引号规范转义。
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
    //MARK:CSV时间
    //csvTime 把毫秒时间戳转换为本地时区的可读日期时间，便于导出后直接查看。
    private fun csvTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()

    //MARK:常量配置
    //定义备份格式版本、允许的 ZIP 条目、各文件大小上限、合法偏好文件名及已知配置清单。
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
