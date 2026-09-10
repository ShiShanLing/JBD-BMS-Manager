package com.bms.jbdmanager.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.FullChargeDeltaSample
import com.bms.jbdmanager.model.FullChargeFingerprint
import com.bms.jbdmanager.model.isEffectivelyFullyCharged
import java.time.Instant
import java.time.ZoneId
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

//MARK:趋势备份
//TrendBackupStats 汇总一次趋势备份的计算或读取结果，调用方无需再从原始字段重复推导。
internal data class TrendBackupStats(
    val sampleCount: Int,
    val dailySummaryCount: Int,
    val fullChargeFingerprintCount: Int,
    val fullChargeDeltaCount: Int = 0
)

//MARK:电池趋势存储
//BatteryTrendStore 封装本地持久化、兼容解析和写回规则，用于处理电池趋势。
internal class BatteryTrendStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    private val appContext = context.applicationContext

    //MARK:创建组件
    //首次创建趋势数据库时建立原始采样、每日摘要、满充指纹和满充压差表及必要索引。
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SAMPLES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_millis INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                total_voltage_v REAL NOT NULL,
                current_a REAL NOT NULL,
                soc_percent REAL NOT NULL,
                maximum_temperature_c REAL,
                cell_delta_mv REAL,
                minimum_cell_mv REAL,
                sample_interval_millis INTEGER NOT NULL DEFAULT $RAW_INTERVAL_MILLIS
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX trend_device_time ON $TABLE_SAMPLES(device_address, timestamp_millis)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX trend_bucket ON $TABLE_SAMPLES(device_address, timestamp_millis, sample_interval_millis)"
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_DAILY (
                day_start_millis INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                total_voltage_v REAL NOT NULL,
                current_a REAL NOT NULL,
                soc_percent REAL NOT NULL,
                maximum_temperature_c REAL,
                cell_delta_mv REAL,
                minimum_cell_mv REAL,
                PRIMARY KEY(device_address, day_start_millis)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_FULL_CHARGE (
                day_start_millis INTEGER NOT NULL,
                captured_at_millis INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                total_voltage_v REAL NOT NULL,
                soc_percent INTEGER NOT NULL,
                maximum_temperature_c REAL,
                minimum_cell_mv INTEGER NOT NULL,
                maximum_cell_mv INTEGER NOT NULL,
                cell_delta_mv INTEGER NOT NULL,
                cell_voltages_mv TEXT NOT NULL,
                PRIMARY KEY(device_address, day_start_millis)
            )
            """.trimIndent()
        )
        createFullChargeDeltaTable(db)
    }

    //MARK:升级数据库
    //onUpgrade 按旧数据库版本依次执行缺失的增量迁移，保留用户已有趋势和健康数据。
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 数据库升级只做增量迁移，不删除旧表；用户长期健康趋势必须跨版本保留。
        if (oldVersion < 2) createLongTermTables(db)
        if (oldVersion < 3) {
            createFullChargeDeltaTable(db)
            backfillFullChargeDeltas(db)
        }
        if (oldVersion < 4) addRemainingCapacityColumn(db)
    }

    //MARK:创建长期表
    //createLongTermTables 创建每日摘要与满充指纹表；使用 IF NOT EXISTS 保证迁移可以安全重试。
    private fun createLongTermTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY (
                day_start_millis INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                total_voltage_v REAL NOT NULL,
                current_a REAL NOT NULL,
                soc_percent REAL NOT NULL,
                maximum_temperature_c REAL,
                cell_delta_mv REAL,
                minimum_cell_mv REAL,
                PRIMARY KEY(device_address, day_start_millis)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FULL_CHARGE (
                day_start_millis INTEGER NOT NULL,
                captured_at_millis INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                total_voltage_v REAL NOT NULL,
                soc_percent INTEGER NOT NULL,
                maximum_temperature_c REAL,
                minimum_cell_mv INTEGER NOT NULL,
                maximum_cell_mv INTEGER NOT NULL,
                cell_delta_mv INTEGER NOT NULL,
                cell_voltages_mv TEXT NOT NULL,
                PRIMARY KEY(device_address, day_start_millis)
            )
            """.trimIndent()
        )
    }

    //MARK:创建充电压差
    //createFullChargeDeltaTable 创建满充压差采样表及设备时间索引；IF NOT EXISTS 使数据库升级可以重复执行。
    private fun createFullChargeDeltaTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FULL_CHARGE_DELTA (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at_millis INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                cell_delta_mv INTEGER NOT NULL,
                total_voltage_v REAL NOT NULL,
                current_a REAL,
                soc_percent INTEGER NOT NULL,
                maximum_temperature_c REAL,
                remaining_capacity_ah REAL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS full_charge_delta_device_time ON $TABLE_FULL_CHARGE_DELTA(device_address, captured_at_millis)"
        )
    }

    //MARK:添加容量
    //addRemainingCapacityColumn 检查满充压差表是否已有剩余容量字段，仅在缺失时执行 ALTER TABLE。
    private fun addRemainingCapacityColumn(db: SQLiteDatabase) {
        // 某些测试安装可能已提前创建字段，先查表结构可让升级脚本具备幂等性。
        val hasColumn = db.rawQuery("PRAGMA table_info($TABLE_FULL_CHARGE_DELTA)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return@use false
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "remaining_capacity_ah") {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            db.execSQL("ALTER TABLE $TABLE_FULL_CHARGE_DELTA ADD COLUMN remaining_capacity_ah REAL")
        }
    }

    //MARK:回填满充压差
    //backfillFullChargeDeltas 把旧满充指纹中 SOC 不低于 99% 的记录迁移为满充压差历史样本。
    private fun backfillFullChargeDeltas(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO $TABLE_FULL_CHARGE_DELTA (
                captured_at_millis, device_address, cell_delta_mv, total_voltage_v,
                current_a, soc_percent, maximum_temperature_c, remaining_capacity_ah
            )
            SELECT captured_at_millis, device_address, cell_delta_mv, total_voltage_v,
                   NULL, soc_percent, maximum_temperature_c, NULL
            FROM $TABLE_FULL_CHARGE
            WHERE soc_percent >= 99
            """.trimIndent()
        )
    }

    @Synchronized
    //MARK:插入采样
    //insert 将一个趋势采样点写入数据库；落入相同时间桶的重复数据按照唯一索引覆盖。
    fun insert(deviceAddress: String, point: BatteryTrendPoint) {
        // 唯一索引按设备、时间桶和采样间隔去重，重复回调覆盖同桶数据而不是生成重复点。
        writableDatabase.insertWithOnConflict(
            TABLE_SAMPLES,
            null,
            point.toValues(deviceAddress, RAW_INTERVAL_MILLIS),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    //MARK:查询趋势
    //query 按设备和时间范围查询趋势数据，并根据跨度聚合采样点以控制图表数据量。
    fun query(
        deviceAddress: String,
        fromMillis: Long,
        toMillis: Long,
        maximumPoints: Int = 360
    ): List<BatteryTrendPoint> {
        if (toMillis <= fromMillis) return emptyList()
        // 查询跨度越长，时间桶越大；返回点数受控，避免多年历史一次加载拖慢图表和占用过多内存。
        val bucketMillis = (((toMillis - fromMillis) / maximumPoints.coerceAtLeast(1))
            .coerceAtLeast(RAW_INTERVAL_MILLIS) / RAW_INTERVAL_MILLIS) * RAW_INTERVAL_MILLIS
        val sql = """
            SELECT MIN(timestamp_millis),
                   AVG(total_voltage_v), AVG(current_a), AVG(soc_percent),
                   AVG(maximum_temperature_c), AVG(cell_delta_mv), AVG(minimum_cell_mv)
            FROM $TABLE_SAMPLES
            WHERE device_address = ? AND timestamp_millis >= ? AND timestamp_millis <= ?
            GROUP BY CAST(timestamp_millis / ? AS INTEGER)
            ORDER BY MIN(timestamp_millis)
        """.trimIndent()
        return readableDatabase.rawQuery(
            sql,
            arrayOf(deviceAddress, fromMillis.toString(), toMillis.toString(), bucketMillis.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BatteryTrendPoint(
                            timestampMillis = cursor.getLong(0),
                            totalVoltageV = cursor.getDouble(1),
                            currentA = cursor.getDouble(2),
                            socPercent = cursor.getDouble(3),
                            maximumTemperatureC = cursor.nullableDouble(4),
                            cellDeltaMv = cursor.nullableDouble(5),
                            minimumCellMv = cursor.nullableDouble(6)
                        )
                    )
                }
            }
        }
    }

    /**
     * 满充时保存每一串电压。每天只保留总压最高的一次，长期不清理，供跨年一致性对比。
     */
    @Synchronized
    //MARK:记录满充纹
    //recordFullChargeFingerprint 在满足有效满充条件时保存当日逐串电压指纹，同一设备每天只保留一份代表记录。
    fun recordFullChargeFingerprint(
        deviceAddress: String,
        info: BmsBasicInfo,
        cells: CellSummary,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (info.stateOfChargePercent < 98 || cells.millivolts.isEmpty()) return false
        val dayStart = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val existingVoltage = readableDatabase.rawQuery(
            "SELECT total_voltage_v FROM $TABLE_FULL_CHARGE WHERE device_address = ? AND day_start_millis = ?",
            arrayOf(deviceAddress, dayStart.toString())
        ).use { if (it.moveToFirst()) it.getDouble(0) else null }
        if (existingVoltage != null && existingVoltage >= info.totalVoltageV) return false
        val values = ContentValues().apply {
            put("day_start_millis", dayStart)
            put("captured_at_millis", nowMillis)
            put("device_address", deviceAddress)
            put("total_voltage_v", info.totalVoltageV)
            put("soc_percent", info.stateOfChargePercent)
            putNullable("maximum_temperature_c", info.temperaturesC.maxOrNull())
            put("minimum_cell_mv", cells.minimumMv ?: return false)
            put("maximum_cell_mv", cells.maximumMv ?: return false)
            put("cell_delta_mv", cells.deltaMv ?: return false)
            put("cell_voltages_mv", cells.millivolts.joinToString(","))
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_FULL_CHARGE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    @Synchronized
    //MARK:读取满充纹
    //loadFullChargeFingerprints 按设备和时间倒序读取满充逐串电压指纹，并还原逗号分隔的单体电压列表。
    fun loadFullChargeFingerprints(deviceAddress: String): List<FullChargeFingerprint> {
        return readableDatabase.rawQuery(
            """
            SELECT captured_at_millis, total_voltage_v, soc_percent, maximum_temperature_c, cell_voltages_mv
            FROM $TABLE_FULL_CHARGE
            WHERE device_address = ?
            ORDER BY captured_at_millis ASC
            """.trimIndent(),
            arrayOf(deviceAddress)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FullChargeFingerprint(
                            capturedAtMillis = cursor.getLong(0),
                            totalVoltageV = cursor.getDouble(1),
                            socPercent = cursor.getInt(2),
                            maximumTemperatureC = cursor.nullableDouble(3),
                            cellVoltagesMv = cursor.getString(4)
                                .split(',')
                                .mapNotNull(String::toIntOrNull)
                        )
                    )
                }
            }
        }
    }

    /**
     * 连接时若接近满充（SOC≥99%，或剩余容量接近满充Ah），记录当时的整组压差。
     * 同一设备两次记录至少间隔两小时，避免自动重连刷屏。
     */
    @Synchronized
    //MARK:记录满充差
    //recordFullChargeDelta 保存一次满充压差、总压、电流、温度及剩余容量样本，供长期一致性趋势分析。
    fun recordFullChargeDelta(
        deviceAddress: String,
        info: BmsBasicInfo,
        cells: CellSummary,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!info.isEffectivelyFullyCharged()) return false
        val delta = cells.deltaMv ?: return false
        val lastCapturedAt = readableDatabase.rawQuery(
            """
            SELECT captured_at_millis FROM $TABLE_FULL_CHARGE_DELTA
            WHERE device_address = ?
            ORDER BY captured_at_millis DESC LIMIT 1
            """.trimIndent(),
            arrayOf(deviceAddress)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
        if (lastCapturedAt != null && nowMillis - lastCapturedAt < FULL_CHARGE_DELTA_MIN_INTERVAL_MILLIS) {
            return false
        }
        val values = ContentValues().apply {
            put("captured_at_millis", nowMillis)
            put("device_address", deviceAddress)
            put("cell_delta_mv", delta)
            put("total_voltage_v", info.totalVoltageV)
            putNullable("current_a", info.currentA)
            put("soc_percent", info.stateOfChargePercent)
            putNullable("maximum_temperature_c", info.temperaturesC.maxOrNull())
            putNullable("remaining_capacity_ah", info.remainingCapacityAh)
        }
        return writableDatabase.insert(TABLE_FULL_CHARGE_DELTA, null, values) != -1L
    }

    @Synchronized
    //MARK:读取满充差
    //loadFullChargeDeltas 按设备读取最近的满充压差样本，并将数据库可空字段保留为空值。
    fun loadFullChargeDeltas(deviceAddress: String): List<FullChargeDeltaSample> {
        return readableDatabase.rawQuery(
            """
            SELECT captured_at_millis, cell_delta_mv, total_voltage_v, current_a,
                   soc_percent, maximum_temperature_c, remaining_capacity_ah
            FROM $TABLE_FULL_CHARGE_DELTA
            WHERE device_address = ?
            ORDER BY captured_at_millis ASC
            """.trimIndent(),
            arrayOf(deviceAddress)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FullChargeDeltaSample(
                            capturedAtMillis = cursor.getLong(0),
                            cellDeltaMv = cursor.getInt(1),
                            totalVoltageV = cursor.getDouble(2),
                            currentA = cursor.nullableDouble(3),
                            socPercent = cursor.getInt(4),
                            maximumTemperatureC = cursor.nullableDouble(5),
                            remainingCapacityAh = cursor.nullableDouble(6)
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    //MARK:读取备份统计
    //backupStats 检查或导出备份，在交付结果前确认文件结构和数据完整性。
    fun backupStats(): TrendBackupStats = TrendBackupStats(
        sampleCount = countRows(TABLE_SAMPLES),
        dailySummaryCount = countRows(TABLE_DAILY),
        fullChargeFingerprintCount = countRows(TABLE_FULL_CHARGE),
        fullChargeDeltaCount = countRows(TABLE_FULL_CHARGE_DELTA)
    )

    @Synchronized
    //MARK:导出快照
    //exportDatabaseSnapshot 检查或导出数据库快照，在交付结果前确认文件结构和数据完整性。
    fun exportDatabaseSnapshot(destination: File) {
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            error("无法覆盖临时数据库文件")
        }
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val escapedPath = destination.absolutePath.replace("'", "''")
        writableDatabase.execSQL("VACUUM INTO '$escapedPath'")
        require(destination.isFile && destination.length() > 0L) { "趋势数据库备份失败" }
    }

    @Synchronized
    //MARK:校验库快照
    //validateDatabaseSnapshot 检查或导出数据库快照，在交付结果前确认文件结构和数据完整性。
    fun validateDatabaseSnapshot(source: File): TrendBackupStats {
        require(source.isFile && source.length() > 0L) { "备份中缺少趋势数据库" }
        val database = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return database.use { db ->
            val schemaVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            require(schemaVersion in 1..DATABASE_VERSION) { "趋势数据库版本不兼容" }
            val integrity = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            require(integrity == "ok") { "趋势数据库完整性校验失败" }
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            require(tables.containsAll(setOf(TABLE_SAMPLES, TABLE_DAILY, TABLE_FULL_CHARGE))) {
                "趋势数据库格式不完整"
            }
            if (schemaVersion >= 3) {
                require(tables.contains(TABLE_FULL_CHARGE_DELTA)) { "趋势数据库格式不完整" }
            }
            TrendBackupStats(
                sampleCount = db.countRows(TABLE_SAMPLES),
                dailySummaryCount = db.countRows(TABLE_DAILY),
                fullChargeFingerprintCount = db.countRows(TABLE_FULL_CHARGE),
                fullChargeDeltaCount = if (tables.contains(TABLE_FULL_CHARGE_DELTA)) {
                    db.countRows(TABLE_FULL_CHARGE_DELTA)
                } else {
                    0
                }
            )
        }
    }

    @Synchronized
    //MARK:替换快照
    //replaceFromDatabaseSnapshot 关闭当前数据库连接后原子替换数据库文件；替换前后均执行完整性校验。
    fun replaceFromDatabaseSnapshot(source: File) {
        validateDatabaseSnapshot(source)
        val target = appContext.getDatabasePath(DATABASE_NAME)
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "$DATABASE_NAME.restore-staging")
        val safety = File(target.parentFile, "$DATABASE_NAME.before-restore")
        staging.delete()
        safety.delete()
        source.copyTo(staging, overwrite = true)
        require(staging.length() == source.length()) { "复制趋势数据库失败" }
        close()
        listOf("-wal", "-shm", "-journal").forEach { suffix -> File(target.absolutePath + suffix).delete() }
        val hadOriginal = target.exists()
        if (hadOriginal && !target.renameTo(safety)) {
            staging.delete()
            error("无法准备趋势数据库恢复")
        }
        try {
            if (!staging.renameTo(target)) error("无法写入趋势数据库")
            validateDatabaseSnapshot(target)
            safety.delete()
        } catch (error: Throwable) {
            close()
            target.delete()
            if (hadOriginal) safety.renameTo(target)
            throw error
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    //MARK:写CSV数据
    //writeCsvEntries 把趋势明细、每日摘要、满充指纹和满充压差分别写成独立 CSV 条目。
    fun writeCsvEntries(zip: ZipOutputStream) {
        writeCsvEntry(zip, "趋势明细.csv") { append ->
            append("时间,时间戳,设备地址,总压V,电流A,SOC%,最高温度C,压差mV,最低单体mV,采样间隔ms\n")
            readableDatabase.rawQuery(
                """
                SELECT timestamp_millis, device_address, total_voltage_v, current_a, soc_percent,
                       maximum_temperature_c, cell_delta_mv, minimum_cell_mv, sample_interval_millis
                FROM $TABLE_SAMPLES ORDER BY timestamp_millis
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    append(
                        listOf(
                            csvTime(cursor.getLong(0)), cursor.getLong(0), cursor.getString(1),
                            cursor.getDouble(2), cursor.getDouble(3), cursor.getDouble(4),
                            cursor.csvNullable(5), cursor.csvNullable(6), cursor.csvNullable(7),
                            cursor.getLong(8)
                        ).joinToString(",", postfix = "\n")
                    )
                }
            }
        }
        writeCsvEntry(zip, "每日趋势摘要.csv") { append ->
            append("日期,时间戳,设备地址,平均总压V,平均电流A,平均SOC%,平均最高温度C,平均压差mV,平均最低单体mV\n")
            readableDatabase.rawQuery(
                """
                SELECT day_start_millis, device_address, total_voltage_v, current_a, soc_percent,
                       maximum_temperature_c, cell_delta_mv, minimum_cell_mv
                FROM $TABLE_DAILY ORDER BY day_start_millis
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    append(
                        listOf(
                            csvTime(cursor.getLong(0)), cursor.getLong(0), cursor.getString(1),
                            cursor.getDouble(2), cursor.getDouble(3), cursor.getDouble(4),
                            cursor.csvNullable(5), cursor.csvNullable(6), cursor.csvNullable(7)
                        ).joinToString(",", postfix = "\n")
                    )
                }
            }
        }
        writeCsvEntry(zip, "满充逐串电压.csv") { append ->
            append("时间,时间戳,设备地址,SOC%,总压V,最高温度C,单体序号,单体电压mV\n")
            readableDatabase.rawQuery(
                """
                SELECT captured_at_millis, device_address, soc_percent, total_voltage_v,
                       maximum_temperature_c, cell_voltages_mv
                FROM $TABLE_FULL_CHARGE ORDER BY captured_at_millis
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val prefix = listOf(
                        csvTime(cursor.getLong(0)), cursor.getLong(0), cursor.getString(1),
                        cursor.getInt(2), cursor.getDouble(3), cursor.csvNullable(4)
                    )
                    cursor.getString(5).split(',').forEachIndexed { index, voltage ->
                        append((prefix + listOf(index + 1, voltage)).joinToString(",", postfix = "\n"))
                    }
                }
            }
        }
        writeCsvEntry(zip, "满充压差.csv") { append ->
            append("时间,时间戳,设备地址,SOC%,压差mV,剩余容量Ah,总压V,电流A,最高温度C\n")
            readableDatabase.rawQuery(
                """
                SELECT captured_at_millis, device_address, soc_percent, cell_delta_mv,
                       remaining_capacity_ah, total_voltage_v, current_a, maximum_temperature_c
                FROM $TABLE_FULL_CHARGE_DELTA ORDER BY captured_at_millis
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    append(
                        listOf(
                            csvTime(cursor.getLong(0)), cursor.getLong(0), cursor.getString(1),
                            cursor.getInt(2), cursor.getInt(3), cursor.csvNullable(4),
                            cursor.getDouble(5), cursor.csvNullable(6), cursor.csvNullable(7)
                        ).joinToString(",", postfix = "\n")
                    )
                }
            }
        }
    }

    //MARK:统计数据行
    //countRows 执行只读计数查询并返回目标表记录数，用于备份校验和恢复预览。
    private fun countRows(table: String): Int = readableDatabase.countRows(table)

    //MARK:统计数据行
    //countRows 执行只读计数查询并返回目标表记录数，用于备份校验和恢复预览。
    private fun SQLiteDatabase.countRows(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    //MARK:写CSV条目
    //writeCsvEntry 创建一个 CSV 条目，依次写入表头和已经完成转义的每一行数据。
    private fun writeCsvEntry(
        zip: ZipOutputStream,
        name: String,
        body: ((String) -> Unit) -> Unit
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val append: (String) -> Unit = { value -> zip.write(value.toByteArray(StandardCharsets.UTF_8)) }
        body(append)
        zip.closeEntry()
    }

    //MARK:读CSV字段
    //csvNullable 把数据库可空数值转换为 CSV 文本，空值输出为空单元格而不是虚假的零。
    private fun android.database.Cursor.csvNullable(index: Int): String =
        if (isNull(index)) "" else getDouble(index).toString()

    //MARK:CSV时间
    //csvTime 把毫秒时间戳转换为本地时区的可读日期时间，便于导出后直接查看。
    private fun csvTime(timestamp: Long): String = "\"${
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }\""

    @Synchronized
    //MARK:维护趋势
    //maintain 按保留策略聚合长期摘要并清理过密旧采样，在保留趋势的同时限制数据库体积。
    fun maintain(nowMillis: Long = System.currentTimeMillis()) {
        val rawCutoff = nowMillis - RAW_RETENTION_MILLIS
        val deleteCutoff = nowMillis - TOTAL_RETENTION_MILLIS
        val localOffsetMillis = ZoneId.systemDefault().rules
            .getOffset(Instant.ofEpochMilli(nowMillis))
            .totalSeconds * 1_000L
        writableDatabase.beginTransaction()
        try {
            // 每日摘要体积很小，永久保留，作为跨月、跨年的长期变化依据。
            writableDatabase.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_DAILY (
                    day_start_millis, device_address, total_voltage_v, current_a, soc_percent,
                    maximum_temperature_c, cell_delta_mv, minimum_cell_mv
                )
                SELECT CAST((timestamp_millis + $localOffsetMillis) / $DAY_MILLIS AS INTEGER) * $DAY_MILLIS - $localOffsetMillis,
                       device_address, AVG(total_voltage_v), AVG(current_a), AVG(soc_percent),
                       AVG(maximum_temperature_c), AVG(cell_delta_mv), AVG(minimum_cell_mv)
                FROM $TABLE_SAMPLES
                GROUP BY device_address, CAST((timestamp_millis + $localOffsetMillis) / $DAY_MILLIS AS INTEGER)
                """.trimIndent()
            )
            writableDatabase.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_SAMPLES (
                    timestamp_millis, device_address, total_voltage_v, current_a, soc_percent,
                    maximum_temperature_c, cell_delta_mv, minimum_cell_mv, sample_interval_millis
                )
                SELECT CAST(timestamp_millis / $ARCHIVE_INTERVAL_MILLIS AS INTEGER) * $ARCHIVE_INTERVAL_MILLIS,
                       device_address, AVG(total_voltage_v), AVG(current_a), AVG(soc_percent),
                       AVG(maximum_temperature_c), AVG(cell_delta_mv), AVG(minimum_cell_mv),
                       $ARCHIVE_INTERVAL_MILLIS
                FROM $TABLE_SAMPLES
                WHERE timestamp_millis >= ? AND timestamp_millis < ?
                      AND sample_interval_millis < $ARCHIVE_INTERVAL_MILLIS
                GROUP BY device_address, CAST(timestamp_millis / $ARCHIVE_INTERVAL_MILLIS AS INTEGER)
                """.trimIndent(),
                arrayOf(deleteCutoff, rawCutoff)
            )
            writableDatabase.delete(
                TABLE_SAMPLES,
                "timestamp_millis >= ? AND timestamp_millis < ? AND sample_interval_millis < ?",
                arrayOf(deleteCutoff.toString(), rawCutoff.toString(), ARCHIVE_INTERVAL_MILLIS.toString())
            )
            writableDatabase.delete(
                TABLE_SAMPLES,
                "timestamp_millis < ?",
                arrayOf(deleteCutoff.toString())
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    //MARK:转换数据行
    //toValues 把趋势采样点转换为 ContentValues，并附加设备地址和采样间隔后写入 SQLite。
    private fun BatteryTrendPoint.toValues(address: String, intervalMillis: Long) = ContentValues().apply {
        put("timestamp_millis", timestampMillis)
        put("device_address", address)
        put("total_voltage_v", totalVoltageV)
        put("current_a", currentA)
        put("soc_percent", socPercent)
        putNullable("maximum_temperature_c", maximumTemperatureC)
        putNullable("cell_delta_mv", cellDeltaMv)
        putNullable("minimum_cell_mv", minimumCellMv)
        put("sample_interval_millis", intervalMillis)
    }

    //MARK:写可空字段
    //putNullable 只在数据库字段值存在时写入 ContentValues，否则显式保存 SQL null。
    private fun ContentValues.putNullable(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    //MARK:读取可空小数
    //nullableDouble 从查询游标读取可空小数；数据库字段为 null 时保持业务值为空。
    private fun android.database.Cursor.nullableDouble(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    //MARK:常量配置
    //定义数据库版本、表列名、采样桶、长期保留期限、满充判定间隔及快照文件大小限制。
    companion object {
        private const val DATABASE_NAME = "battery_trends.db"
        private const val DATABASE_VERSION = 4
        private const val TABLE_SAMPLES = "trend_samples"
        private const val TABLE_DAILY = "trend_daily"
        private const val TABLE_FULL_CHARGE = "full_charge_fingerprints"
        private const val TABLE_FULL_CHARGE_DELTA = "full_charge_deltas"
        private const val RAW_INTERVAL_MILLIS = 10_000L
        private const val ARCHIVE_INTERVAL_MILLIS = 5 * 60 * 1_000L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        private const val RAW_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        private const val TOTAL_RETENTION_MILLIS = 30 * 24 * 60 * 60 * 1_000L
        private const val FULL_CHARGE_DELTA_MIN_INTERVAL_MILLIS = 2 * 60 * 60 * 1_000L
    }
}
