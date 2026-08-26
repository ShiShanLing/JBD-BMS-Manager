package com.bms.jbdmanager.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.FullChargeFingerprint
import java.time.Instant
import java.time.ZoneId
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class TrendBackupStats(
    val sampleCount: Int,
    val dailySummaryCount: Int,
    val fullChargeFingerprintCount: Int
)

internal class BatteryTrendStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    private val appContext = context.applicationContext

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createLongTermTables(db)
    }

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

    @Synchronized
    fun insert(deviceAddress: String, point: BatteryTrendPoint) {
        writableDatabase.insertWithOnConflict(
            TABLE_SAMPLES,
            null,
            point.toValues(deviceAddress, RAW_INTERVAL_MILLIS),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun query(
        deviceAddress: String,
        fromMillis: Long,
        toMillis: Long,
        maximumPoints: Int = 360
    ): List<BatteryTrendPoint> {
        if (toMillis <= fromMillis) return emptyList()
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

    @Synchronized
    fun backupStats(): TrendBackupStats = TrendBackupStats(
        sampleCount = countRows(TABLE_SAMPLES),
        dailySummaryCount = countRows(TABLE_DAILY),
        fullChargeFingerprintCount = countRows(TABLE_FULL_CHARGE)
    )

    @Synchronized
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
            TrendBackupStats(
                sampleCount = db.countRows(TABLE_SAMPLES),
                dailySummaryCount = db.countRows(TABLE_DAILY),
                fullChargeFingerprintCount = db.countRows(TABLE_FULL_CHARGE)
            )
        }
    }

    @Synchronized
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
    }

    private fun countRows(table: String): Int = readableDatabase.countRows(table)

    private fun SQLiteDatabase.countRows(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

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

    private fun android.database.Cursor.csvNullable(index: Int): String =
        if (isNull(index)) "" else getDouble(index).toString()

    private fun csvTime(timestamp: Long): String = "\"${
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }\""

    @Synchronized
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

    private fun ContentValues.putNullable(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun android.database.Cursor.nullableDouble(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    companion object {
        private const val DATABASE_NAME = "battery_trends.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_SAMPLES = "trend_samples"
        private const val TABLE_DAILY = "trend_daily"
        private const val TABLE_FULL_CHARGE = "full_charge_fingerprints"
        private const val RAW_INTERVAL_MILLIS = 10_000L
        private const val ARCHIVE_INTERVAL_MILLIS = 5 * 60 * 1_000L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        private const val RAW_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        private const val TOTAL_RETENTION_MILLIS = 30 * 24 * 60 * 60 * 1_000L
    }
}
