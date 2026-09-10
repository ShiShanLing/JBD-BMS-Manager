package com.bms.jbdmanager.storage

import android.content.Context
import android.content.SharedPreferences
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.model.LastBmsSnapshot
import com.bms.jbdmanager.model.MileageHistoryState
import com.bms.jbdmanager.model.RegenerationPeak
import com.bms.jbdmanager.model.SpeedRangeStats
import com.bms.jbdmanager.model.TripSessionRecord
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.defaultSpeedRangeStats
import com.bms.jbdmanager.trip.TripStateStore
import org.json.JSONArray
import org.json.JSONObject

//MARK:快照存储
//LastSnapshotStore 封装本地持久化、兼容解析和写回规则，用于处理最后一次快照。
internal class LastSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mileageHistoryStore = MileageHistoryStore(context)
    private val tripStateStore = TripStateStore(context)

    //MARK:保存状态
    //从当前 UI 状态提取最后一包完整 BMS 数据、单体、参数和行程，合并历史后覆盖离线快照。
    fun save(state: BmsUiState, nowMillis: Long = System.currentTimeMillis()): LastBmsSnapshot? {
        // 没有成功解析基本信息时不保存，防止连接中间态覆盖上一份完整、可查看的离线状态。
        val info = state.basicInfo ?: return null
        val mileageHistory = mergeMileageHistory(state.mileageHistory)
        val snapshot = LastBmsSnapshot(
            savedAtMillis = nowMillis,
            deviceAddress = state.connectedAddress,
            deviceName = state.connectedName,
            modelName = state.modelName,
            chipType = state.chipType,
            protocolProfile = state.protocolProfile,
            detectedProtocol = state.detectedProtocol,
            basicInfo = info,
            cells = state.cells,
            protectionParams = state.protectionParams,
            gpsSpeed = state.gpsSpeed,
            trip = selectSnapshotTrip(state.trip, mileageHistory),
            mileageHistory = mileageHistory
        )
        write(snapshot)
        return snapshot
    }

    //MARK:读取记录
    //逐字段还原最后一次电池快照；关键电压或容量缺失时拒绝生成可能误导用户的离线状态。
    fun load(): LastBmsSnapshot? {
        // KEY_EXISTS 最后才随完整写入建立；缺少关键字段则视为无快照，而不是用 0 拼出虚假电池数据。
        if (!preferences.getBoolean(KEY_EXISTS, false)) return null
        val savedAt = preferences.getLong(KEY_SAVED_AT, -1L).takeIf { it >= 0L } ?: return null
        val basic = BmsBasicInfo(
            totalVoltageV = preferences.double(KEY_TOTAL_VOLTAGE) ?: return null,
            currentA = preferences.double(KEY_CURRENT) ?: 0.0,
            remainingCapacityAh = preferences.double(KEY_REMAINING_AH) ?: return null,
            nominalCapacityAh = preferences.double(KEY_NOMINAL_AH) ?: return null,
            fullChargeCapacityAh = preferences.double(KEY_FULL_CHARGE_AH),
            stateOfChargePercent = preferences.getInt(KEY_SOC, 0).coerceIn(0, 100),
            cycleCount = preferences.getInt(KEY_CYCLES, 0),
            temperaturesC = preferences.getString(KEY_TEMPERATURES, null).toDoubleList(),
            cellCount = preferences.getInt(KEY_CELL_COUNT, 0),
            chargeMosEnabled = preferences.getBoolean(KEY_CHARGE_MOS, false),
            dischargeMosEnabled = preferences.getBoolean(KEY_DISCHARGE_MOS, false),
            balancingMask = preferences.getLong(KEY_BALANCING_MASK, 0L),
            protectionMask = preferences.getInt(KEY_PROTECTION_MASK, 0),
            alarmMask = preferences.optionalInt(KEY_ALARM_MASK),
            softwareVersion = preferences.getString(KEY_SOFTWARE_VERSION, null).orEmpty(),
            productionDate = preferences.getString(KEY_PRODUCTION_DATE, null),
            humidityPercent = preferences.optionalInt(KEY_HUMIDITY),
            balancingCurrentMa = preferences.optionalInt(KEY_BALANCING_CURRENT),
            updatedAtMillis = preferences.getLong(KEY_DATA_UPDATED_AT, savedAt)
        )
        val cells = preferences.getString(KEY_CELLS, null)
            ?.takeIf(String::isNotBlank)
            ?.split(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.takeIf(List<Int>::isNotEmpty)
            ?.let { CellSummary(it, preferences.getLong(KEY_CELLS_UPDATED_AT, savedAt)) }
        val savedSpeedRangeStats = preferences.getString(KEY_SPEED_RANGE_STATS, null)
            .toSpeedRangeStats()
            ?: tripStateStore.load().speedRangeStats
        val savedTrip = TripState(
            isTracking = false,
            startedAtMillis = preferences.optionalLong(KEY_TRIP_STARTED_AT),
            distanceMeters = preferences.double(KEY_TRIP_DISTANCE) ?: 0.0,
            startSocPercent = preferences.optionalInt(KEY_TRIP_START_SOC),
            currentSocPercent = preferences.optionalInt(KEY_TRIP_CURRENT_SOC),
            startRemainingAh = preferences.double(KEY_TRIP_START_AH),
            currentRemainingAh = preferences.double(KEY_TRIP_CURRENT_AH),
            integratedConsumedAh = preferences.double(KEY_TRIP_CONSUMED_AH) ?: 0.0,
            integratedConsumedWh = preferences.double(KEY_TRIP_CONSUMED_WH) ?: 0.0,
            currentA = preferences.double(KEY_TRIP_CURRENT) ?: 0.0,
            currentSpeedKmh = 0.0,
            locationAccuracyMeters = preferences.optionalFloat(KEY_TRIP_ACCURACY),
            validLocationPoints = preferences.getInt(KEY_TRIP_POINTS, 0),
            lastLocationAtMillis = preferences.optionalLong(KEY_TRIP_LAST_LOCATION),
            gpsMessage = "最后状态保存时的行程",
            maximumRegeneration = preferences.double(KEY_TRIP_REGEN_POWER)
                ?.takeIf { it > 0.0 }
                ?.let { powerW ->
                    RegenerationPeak(
                        currentA = preferences.double(KEY_TRIP_REGEN_CURRENT) ?: 0.0,
                        powerW = powerW,
                        speedKmh = preferences.double(KEY_TRIP_REGEN_SPEED) ?: 0.0,
                        recordedAtMillis = preferences.optionalLong(KEY_TRIP_REGEN_AT) ?: 0L
                    )
                },
            speedRangeStats = savedSpeedRangeStats
        )
        val mileageHistory = mergeMileageHistory(
            preferences.getString(KEY_MILEAGE_HISTORY, null).toMileageHistory()
        )
        val trip = selectSnapshotTrip(savedTrip, mileageHistory)
        return LastBmsSnapshot(
            savedAtMillis = savedAt,
            deviceAddress = preferences.getString(KEY_DEVICE_ADDRESS, null),
            deviceName = preferences.getString(KEY_DEVICE_NAME, null),
            modelName = preferences.getString(KEY_MODEL_NAME, null),
            chipType = preferences.getString(KEY_CHIP_TYPE, null),
            protocolProfile = preferences.getString(KEY_PROTOCOL_PROFILE, null) ?: "未记录",
            detectedProtocol = preferences.getString(KEY_DETECTED_PROTOCOL, null),
            basicInfo = basic,
            cells = cells,
            protectionParams = preferences.getString(KEY_PROTECTION_PARAMS, null).toProtectionParams(),
            gpsSpeed = GpsSpeedState(
                currentKmh = preferences.double(KEY_GPS_CURRENT) ?: 0.0,
                average5SecondsKmh = preferences.double(KEY_GPS_AVERAGE) ?: 0.0,
                maximumKmh = preferences.double(KEY_GPS_MAXIMUM) ?: 0.0
            ),
            trip = trip,
            mileageHistory = mileageHistory
        )
    }

    //MARK:选择快照行程
    //selectSnapshotTrip 优先选择达到有效距离的当前行程；当前数据过短时回退到最近一次已完成行程。
    private fun selectSnapshotTrip(trip: TripState, mileageHistory: MileageHistoryState): TripState {
        val currentTrip = trip.copy(isTracking = false, currentSpeedKmh = 0.0)
        // 当前行程达到有效距离时优先展示；过短或刚启动的行程则回退到最近完成记录，避免总显示 0 km。
        if (currentTrip.distanceMeters >= MINIMUM_MEANINGFUL_TRIP_METERS) return currentTrip

        val latestCompleted = mileageHistory.sessions.maxByOrNull { it.finishedAtMillis }
            ?: return currentTrip
        return currentTrip.copy(
            startedAtMillis = latestCompleted.startedAtMillis,
            distanceMeters = latestCompleted.distanceMeters,
            startSocPercent = null,
            startRemainingAh = null,
            integratedConsumedAh = latestCompleted.consumedAh,
            integratedConsumedWh = latestCompleted.consumedWh,
            currentA = 0.0,
            currentSpeedKmh = 0.0,
            maximumRegeneration = latestCompleted.maximumRegeneration,
            gpsMessage = "最近一次已完成行程"
        )
    }

    //MARK:合并里程历史
    //mergeMileageHistory 合并快照和独立历史仓库中的行程，按起止时间去重、倒序排列并限制总数。
    private fun mergeMileageHistory(saved: MileageHistoryState): MileageHistoryState {
        // 快照副本与独立历史仓库可能包含相同记录，按起止时间去重后再限制数量，防止重复累计。
        val sessions = (saved.sessions + mileageHistoryStore.loadSessions())
            .distinctBy { it.startedAtMillis to it.finishedAtMillis }
            .sortedByDescending { it.startedAtMillis }
            .take(MAX_MILEAGE_SESSIONS)
        return saved.copy(sessions = sessions)
    }

    //MARK:写入数据
    //清空旧快照后在同一 Editor 中写入全部字段，防止模型删减字段后残留旧值被误读。
    private fun write(snapshot: LastBmsSnapshot) {
        val info = snapshot.basicInfo
        val trip = snapshot.trip
        // 单次 Editor 提交整份快照，旧字段先清除，避免模型字段减少后残留值被新版本误读。
        preferences.edit().clear()
            .putBoolean(KEY_EXISTS, true)
            .putLong(KEY_SAVED_AT, snapshot.savedAtMillis)
            .putString(KEY_DEVICE_ADDRESS, snapshot.deviceAddress)
            .putString(KEY_DEVICE_NAME, snapshot.deviceName)
            .putString(KEY_MODEL_NAME, snapshot.modelName)
            .putString(KEY_CHIP_TYPE, snapshot.chipType)
            .putString(KEY_PROTOCOL_PROFILE, snapshot.protocolProfile)
            .putString(KEY_DETECTED_PROTOCOL, snapshot.detectedProtocol)
            .putDouble(KEY_TOTAL_VOLTAGE, info.totalVoltageV)
            .putDouble(KEY_CURRENT, info.currentA)
            .putDouble(KEY_REMAINING_AH, info.remainingCapacityAh)
            .putDouble(KEY_NOMINAL_AH, info.nominalCapacityAh)
            .putOptionalDouble(KEY_FULL_CHARGE_AH, info.fullChargeCapacityAh)
            .putInt(KEY_SOC, info.stateOfChargePercent)
            .putInt(KEY_CYCLES, info.cycleCount)
            .putString(KEY_TEMPERATURES, info.temperaturesC.joinToString(","))
            .putInt(KEY_CELL_COUNT, info.cellCount)
            .putBoolean(KEY_CHARGE_MOS, info.chargeMosEnabled)
            .putBoolean(KEY_DISCHARGE_MOS, info.dischargeMosEnabled)
            .putLong(KEY_BALANCING_MASK, info.balancingMask)
            .putInt(KEY_PROTECTION_MASK, info.protectionMask)
            .putOptionalInt(KEY_ALARM_MASK, info.alarmMask)
            .putString(KEY_SOFTWARE_VERSION, info.softwareVersion)
            .putString(KEY_PRODUCTION_DATE, info.productionDate)
            .putOptionalInt(KEY_HUMIDITY, info.humidityPercent)
            .putOptionalInt(KEY_BALANCING_CURRENT, info.balancingCurrentMa)
            .putLong(KEY_DATA_UPDATED_AT, info.updatedAtMillis)
            .putString(KEY_CELLS, snapshot.cells?.millivolts?.joinToString(","))
            .putLong(KEY_CELLS_UPDATED_AT, snapshot.cells?.updatedAtMillis ?: -1L)
            .putString(KEY_PROTECTION_PARAMS, snapshot.protectionParams?.toJson()?.toString())
            .putDouble(KEY_GPS_CURRENT, snapshot.gpsSpeed.currentKmh)
            .putDouble(KEY_GPS_AVERAGE, snapshot.gpsSpeed.average5SecondsKmh)
            .putDouble(KEY_GPS_MAXIMUM, snapshot.gpsSpeed.maximumKmh)
            .putOptionalLong(KEY_TRIP_STARTED_AT, trip.startedAtMillis)
            .putDouble(KEY_TRIP_DISTANCE, trip.distanceMeters)
            .putOptionalInt(KEY_TRIP_START_SOC, trip.startSocPercent)
            .putOptionalInt(KEY_TRIP_CURRENT_SOC, trip.currentSocPercent)
            .putOptionalDouble(KEY_TRIP_START_AH, trip.startRemainingAh)
            .putOptionalDouble(KEY_TRIP_CURRENT_AH, trip.currentRemainingAh)
            .putDouble(KEY_TRIP_CONSUMED_AH, trip.integratedConsumedAh)
            .putDouble(KEY_TRIP_CONSUMED_WH, trip.integratedConsumedWh)
            .putDouble(KEY_TRIP_CURRENT, trip.currentA)
            .putOptionalDouble(KEY_TRIP_REGEN_CURRENT, trip.maximumRegeneration?.currentA)
            .putOptionalDouble(KEY_TRIP_REGEN_POWER, trip.maximumRegeneration?.powerW)
            .putOptionalDouble(KEY_TRIP_REGEN_SPEED, trip.maximumRegeneration?.speedKmh)
            .putOptionalLong(KEY_TRIP_REGEN_AT, trip.maximumRegeneration?.recordedAtMillis)
            .putOptionalFloat(KEY_TRIP_ACCURACY, trip.locationAccuracyMeters)
            .putInt(KEY_TRIP_POINTS, trip.validLocationPoints)
            .putOptionalLong(KEY_TRIP_LAST_LOCATION, trip.lastLocationAtMillis)
            .putString(KEY_SPEED_RANGE_STATS, trip.speedRangeStats.toJson())
            .putString(KEY_MILEAGE_HISTORY, snapshot.mileageHistory.toJson())
            .commit()
    }

    //MARK:编码JSON
    //把只读保护阈值模型编码为 JSON；未知阈值保持缺失，而不是写成零。
    private fun JbdProtectionParams.toJson(): JSONObject = JSONObject().apply {
        putOptionalDouble("fullChargeVoltageV", fullChargeVoltageV)
        putOptionalDouble("cellOvervoltageV", cellOvervoltageV)
        putOptionalDouble("cellOvervoltageReleaseV", cellOvervoltageReleaseV)
        putOptionalDouble("cellUndervoltageV", cellUndervoltageV)
        putOptionalDouble("cellUndervoltageReleaseV", cellUndervoltageReleaseV)
        putOptionalDouble("packOvervoltageV", packOvervoltageV)
        putOptionalDouble("packOvervoltageReleaseV", packOvervoltageReleaseV)
        putOptionalDouble("packUndervoltageV", packUndervoltageV)
        putOptionalDouble("packUndervoltageReleaseV", packUndervoltageReleaseV)
        putOptionalDouble("chargeOvercurrentA", chargeOvercurrentA)
        putOptionalDouble("dischargeOvercurrentA", dischargeOvercurrentA)
        putOptionalDouble("chargeHighTempC", chargeHighTempC)
        putOptionalDouble("chargeHighTempReleaseC", chargeHighTempReleaseC)
        putOptionalDouble("chargeLowTempC", chargeLowTempC)
        putOptionalDouble("chargeLowTempReleaseC", chargeLowTempReleaseC)
        putOptionalDouble("dischargeHighTempC", dischargeHighTempC)
        putOptionalDouble("dischargeHighTempReleaseC", dischargeHighTempReleaseC)
        putOptionalDouble("dischargeLowTempC", dischargeLowTempC)
        putOptionalDouble("dischargeLowTempReleaseC", dischargeLowTempReleaseC)
    }

    //MARK:还原保护参数
    //把快照中的保护参数 JSON 还原为只读参数模型；空文本或任一字段解析失败时返回空值。
    private fun String?.toProtectionParams(): JbdProtectionParams? {
        if (this.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(this)
            JbdProtectionParams(
                fullChargeVoltageV = json.optionalDouble("fullChargeVoltageV"),
                cellOvervoltageV = json.optionalDouble("cellOvervoltageV"),
                cellOvervoltageReleaseV = json.optionalDouble("cellOvervoltageReleaseV"),
                cellUndervoltageV = json.optionalDouble("cellUndervoltageV"),
                cellUndervoltageReleaseV = json.optionalDouble("cellUndervoltageReleaseV"),
                packOvervoltageV = json.optionalDouble("packOvervoltageV"),
                packOvervoltageReleaseV = json.optionalDouble("packOvervoltageReleaseV"),
                packUndervoltageV = json.optionalDouble("packUndervoltageV"),
                packUndervoltageReleaseV = json.optionalDouble("packUndervoltageReleaseV"),
                chargeOvercurrentA = json.optionalDouble("chargeOvercurrentA"),
                dischargeOvercurrentA = json.optionalDouble("dischargeOvercurrentA"),
                chargeHighTempC = json.optionalDouble("chargeHighTempC"),
                chargeHighTempReleaseC = json.optionalDouble("chargeHighTempReleaseC"),
                chargeLowTempC = json.optionalDouble("chargeLowTempC"),
                chargeLowTempReleaseC = json.optionalDouble("chargeLowTempReleaseC"),
                dischargeHighTempC = json.optionalDouble("dischargeHighTempC"),
                dischargeHighTempReleaseC = json.optionalDouble("dischargeHighTempReleaseC"),
                dischargeLowTempC = json.optionalDouble("dischargeLowTempC"),
                dischargeLowTempReleaseC = json.optionalDouble("dischargeLowTempReleaseC")
            )
        }.getOrNull()
    }

    //MARK:写可选小数
    //putOptionalDouble 仅在小数值存在时写入目标 JSON 或偏好数据，避免用零覆盖未知状态。
    private fun JSONObject.putOptionalDouble(key: String, value: Double?) {
        if (value != null) put(key, value)
    }

    //MARK:可选小数
    //optionalDouble 读取可选小数字段；键缺失或值为 JSON null 时返回 Kotlin null。
    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    //MARK:编码JSON
    //把各速度区间的距离、时间与耗电样本编码为 JSON 数组，供离线续航页恢复。
    private fun List<SpeedRangeStats>.toJson(): String = JSONArray().apply {
        forEach { stats ->
            put(JSONObject().apply {
                put("targetSpeedKmh", stats.targetSpeedKmh)
                put("effectiveDistanceMeters", stats.effectiveDistanceMeters)
                put("effectiveDurationSeconds", stats.effectiveDurationSeconds)
                put("consumedAh", stats.consumedAh)
                put("consumedWh", stats.consumedWh)
            })
        }
    }.toString()

    //MARK:还原分速续航
    //把快照中的分速度续航 JSON 还原为样本列表，并过滤缺少速度边界的无效项目。
    private fun String?.toSpeedRangeStats(): List<SpeedRangeStats>? {
        if (this.isNullOrBlank()) return null
        return runCatching {
            val saved = JSONArray(this)
            val bySpeed = buildMap {
                for (index in 0 until saved.length()) {
                    val item = saved.getJSONObject(index)
                    val stats = SpeedRangeStats(
                        targetSpeedKmh = item.getInt("targetSpeedKmh"),
                        effectiveDistanceMeters = item.optDouble("effectiveDistanceMeters", 0.0),
                        effectiveDurationSeconds = item.optDouble("effectiveDurationSeconds", 0.0),
                        consumedAh = item.optDouble("consumedAh", 0.0),
                        consumedWh = item.optDouble("consumedWh", 0.0)
                    )
                    put(stats.targetSpeedKmh, stats)
                }
            }
            defaultSpeedRangeStats().map { bySpeed[it.targetSpeedKmh] ?: it }
        }.getOrNull()
    }

    //MARK:编码JSON
    //把已完成行程和当前活动行程摘要编码为 JSON，保存离线页面所需里程上下文。
    private fun MileageHistoryState.toJson(): String = JSONObject().apply {
        put("activeDistanceMeters", activeTripDistanceMeters)
        activeTripStartedAtMillis?.let { put("activeStartedAtMillis", it) }
        put("sessions", JSONArray().apply {
            sessions.forEach { session ->
                put(JSONObject().apply {
                    put("startedAtMillis", session.startedAtMillis)
                    put("finishedAtMillis", session.finishedAtMillis)
                    put("distanceMeters", session.distanceMeters)
                    put("consumedAh", session.consumedAh)
                    put("consumedWh", session.consumedWh)
                    session.maximumRegeneration?.let { peak ->
                        put("maximumRegeneration", JSONObject()
                            .put("currentA", peak.currentA)
                            .put("powerW", peak.powerW)
                            .put("speedKmh", peak.speedKmh)
                            .put("recordedAtMillis", peak.recordedAtMillis))
                    }
                })
            }
        })
    }.toString()

    //MARK:还原里程历史
    //还原快照携带的行程历史和活动行程摘要；旧版本无此字段或解析失败时返回空历史。
    private fun String?.toMileageHistory(): MileageHistoryState {
        if (this.isNullOrBlank()) return MileageHistoryState()
        return runCatching {
            val root = JSONObject(this)
            val array = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TripSessionRecord(
                            startedAtMillis = item.getLong("startedAtMillis"),
                            finishedAtMillis = item.getLong("finishedAtMillis"),
                            distanceMeters = item.getDouble("distanceMeters"),
                            consumedAh = item.optDouble("consumedAh", 0.0),
                            consumedWh = item.optDouble("consumedWh", 0.0),
                            maximumRegeneration = item.optJSONObject("maximumRegeneration")?.let { peak ->
                                RegenerationPeak(
                                    currentA = peak.optDouble("currentA", 0.0),
                                    powerW = peak.optDouble("powerW", 0.0),
                                    speedKmh = peak.optDouble("speedKmh", 0.0),
                                    recordedAtMillis = peak.optLong("recordedAtMillis", 0L)
                                )
                            }
                        )
                    )
                }
            }
            MileageHistoryState(
                sessions = sessions,
                activeTripDistanceMeters = root.optDouble("activeDistanceMeters", 0.0),
                activeTripStartedAtMillis = root.optLong("activeStartedAtMillis")
                    .takeIf { root.has("activeStartedAtMillis") }
            )
        }.getOrDefault(MileageHistoryState())
    }

    //MARK:读取小数
    //double 读取以字符串保存的 Double，格式错误时返回空值而不是抛出异常。
    private fun SharedPreferences.double(key: String): Double? = getString(key, null)?.toDoubleOrNull()
    //MARK:解析小数列表
    //toDoubleList 把逗号分隔的小数字符串还原为列表；空值和无法解析的片段会被安全忽略。
    private fun String?.toDoubleList(): List<Double> = this?.split(',')?.mapNotNull(String::toDoubleOrNull).orEmpty()
    //MARK:可选整数
    //optionalInt 读取可选整数字段；键缺失或值为 JSON null 时返回 Kotlin null。
    private fun SharedPreferences.optionalInt(key: String): Int? = if (contains(key)) getInt(key, 0) else null
    //MARK:可选长整
    //optionalLong 读取可选长整数字段；键缺失或值为 JSON null 时返回 Kotlin null。
    private fun SharedPreferences.optionalLong(key: String): Long? = if (contains(key)) getLong(key, 0L) else null
    //MARK:可选浮点
    //optionalFloat 读取可选浮点字段；键缺失时返回 Kotlin null，以区分真实的零值。
    private fun SharedPreferences.optionalFloat(key: String): Float? = if (contains(key)) getFloat(key, 0f) else null
    //MARK:写入小数
    //putDouble 把 Double 转成字符串保存，避免 SharedPreferences 只支持 Float 导致精度损失。
    private fun SharedPreferences.Editor.putDouble(key: String, value: Double) = putString(key, value.toString())
    //MARK:写可选小数
    //putOptionalDouble 仅在小数值存在时写入目标 JSON 或偏好数据，避免用零覆盖未知状态。
    private fun SharedPreferences.Editor.putOptionalDouble(key: String, value: Double?) =
        value?.let { putDouble(key, it) } ?: remove(key)
    //MARK:写可选整数
    //putOptionalInt 仅在整数值存在时写入偏好数据，空值则保持对应键不存在。
    private fun SharedPreferences.Editor.putOptionalInt(key: String, value: Int?) =
        value?.let { putInt(key, it) } ?: remove(key)
    //MARK:写可选长整
    //putOptionalLong 仅在长整数值存在时写入偏好数据，空值则保持对应键不存在。
    private fun SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) =
        value?.let { putLong(key, it) } ?: remove(key)
    //MARK:写可选浮点
    //putOptionalFloat 仅在浮点值存在时写入偏好数据，空值则保持对应键不存在。
    private fun SharedPreferences.Editor.putOptionalFloat(key: String, value: Float?) =
        value?.let { putFloat(key, it) } ?: remove(key)

    //MARK:常量配置
    //声明离线快照全部字段键、有效行程最小距离和最多合并的历史数量，确保读写使用同一协议。
    private companion object {
        const val PREFERENCES_NAME = "jbd_last_snapshot"
        const val KEY_EXISTS = "exists"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_CHIP_TYPE = "chip_type"
        const val KEY_PROTOCOL_PROFILE = "protocol_profile"
        const val KEY_DETECTED_PROTOCOL = "detected_protocol"
        const val KEY_TOTAL_VOLTAGE = "total_voltage"
        const val KEY_CURRENT = "current"
        const val KEY_REMAINING_AH = "remaining_ah"
        const val KEY_NOMINAL_AH = "nominal_ah"
        const val KEY_FULL_CHARGE_AH = "full_charge_ah"
        const val KEY_SOC = "soc"
        const val KEY_CYCLES = "cycles"
        const val KEY_TEMPERATURES = "temperatures"
        const val KEY_CELL_COUNT = "cell_count"
        const val KEY_CHARGE_MOS = "charge_mos"
        const val KEY_DISCHARGE_MOS = "discharge_mos"
        const val KEY_BALANCING_MASK = "balancing_mask"
        const val KEY_PROTECTION_MASK = "protection_mask"
        const val KEY_ALARM_MASK = "alarm_mask"
        const val KEY_SOFTWARE_VERSION = "software_version"
        const val KEY_PRODUCTION_DATE = "production_date"
        const val KEY_HUMIDITY = "humidity"
        const val KEY_BALANCING_CURRENT = "balancing_current"
        const val KEY_DATA_UPDATED_AT = "data_updated_at"
        const val KEY_CELLS = "cells"
        const val KEY_CELLS_UPDATED_AT = "cells_updated_at"
        const val KEY_PROTECTION_PARAMS = "protection_params"
        const val KEY_GPS_CURRENT = "gps_current"
        const val KEY_GPS_AVERAGE = "gps_average"
        const val KEY_GPS_MAXIMUM = "gps_maximum"
        const val KEY_TRIP_STARTED_AT = "trip_started_at"
        const val KEY_TRIP_DISTANCE = "trip_distance"
        const val KEY_TRIP_START_SOC = "trip_start_soc"
        const val KEY_TRIP_CURRENT_SOC = "trip_current_soc"
        const val KEY_TRIP_START_AH = "trip_start_ah"
        const val KEY_TRIP_CURRENT_AH = "trip_current_ah"
        const val KEY_TRIP_CONSUMED_AH = "trip_consumed_ah"
        const val KEY_TRIP_CONSUMED_WH = "trip_consumed_wh"
        const val KEY_TRIP_CURRENT = "trip_current"
        const val KEY_TRIP_REGEN_CURRENT = "trip_regen_current"
        const val KEY_TRIP_REGEN_POWER = "trip_regen_power"
        const val KEY_TRIP_REGEN_SPEED = "trip_regen_speed"
        const val KEY_TRIP_REGEN_AT = "trip_regen_at"
        const val KEY_TRIP_ACCURACY = "trip_accuracy"
        const val KEY_TRIP_POINTS = "trip_points"
        const val KEY_TRIP_LAST_LOCATION = "trip_last_location"
        const val KEY_SPEED_RANGE_STATS = "speed_range_stats"
        const val KEY_MILEAGE_HISTORY = "mileage_history"
        const val MINIMUM_MEANINGFUL_TRIP_METERS = 10.0
        const val MAX_MILEAGE_SESSIONS = 500
    }
}
