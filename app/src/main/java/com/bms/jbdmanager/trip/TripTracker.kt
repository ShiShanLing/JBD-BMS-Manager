package com.bms.jbdmanager.trip

import android.content.Context
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.RangeTestState
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.TripTrackingMode
import com.bms.jbdmanager.model.defaultSpeedRangeStats
import com.bms.jbdmanager.model.resolveMileageCountdownReachedAt
import com.bms.jbdmanager.model.updatedRegenerationPeak
import com.bms.jbdmanager.storage.MileageHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

//MARK:行程跟踪
//TripTracker 提供进程内共享的行程能力，并集中维护其状态、常量或纯计算入口。
object TripTracker {
    private const val MAX_BMS_SAMPLE_GAP_MS = 15_000L

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var stateStore: TripStateStore
    private lateinit var mileageHistoryStore: MileageHistoryStore
    private var lastBmsAtMillis: Long? = null
    private var lastCurrentA: Double? = null
    private var lastVoltageV: Double? = null
    private var autoStartSuppressed = false
    private var lastLocationPersistAtMillis = 0L

    @Synchronized
    //MARK:初始化功能
    //initialize 计算或控制initialize，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        stateStore = TripStateStore(appContext)
        mileageHistoryStore = MileageHistoryStore(appContext)
        _state.value = stateStore.load()
        initialized = true
    }

    @Synchronized
    //MARK:开始行程
    //begin 建立begin的新会话基线，重置本次数据但保留允许跨行程累计的历史样本。
    fun begin(info: BmsBasicInfo, nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        if (!_state.value.isTracking) {
            // 分速度区间的长期样本跨行程保留；本次距离、SOC 和容量则必须从新连接重新建立基准。
            val retainedSpeedRangeStats = _state.value.speedRangeStats
            _state.value = TripState(
                isTracking = true,
                trackingMode = TripTrackingMode.Bms,
                startedAtMillis = nowMillis,
                startSocPercent = info.stateOfChargePercent,
                currentSocPercent = info.stateOfChargePercent,
                startRemainingAh = info.remainingCapacityAh,
                currentRemainingAh = info.remainingCapacityAh,
                currentA = info.currentA,
                gpsMessage = "正在等待 GPS 定位",
                speedRangeStats = retainedSpeedRangeStats
            )
        }
        lastBmsAtMillis = nowMillis
        lastCurrentA = info.currentA
        lastVoltageV = info.totalVoltageV
        persist()
    }

    @Synchronized
    //MARK:纯GPS行程
    //beginMileageOnly 建立里程的新会话基线，重置本次数据但保留允许跨行程累计的历史样本。
    fun beginMileageOnly(
        nowMillis: Long = System.currentTimeMillis(),
        countdownTargetKm: Int = 30
    ) {
        ensureInitialized()
        if (_state.value.isTracking && _state.value.isMileageOnly) return
        // 租用电池模式只累计 GPS 里程，不把未知电池的电流和容量混入自己的续航样本。
        val retainedSpeedRangeStats = _state.value.speedRangeStats
        _state.value = TripState(
            isTracking = true,
            trackingMode = TripTrackingMode.MileageOnly,
            startedAtMillis = nowMillis,
            gpsMessage = "正在等待 GPS 定位",
            mileageCountdownTargetKm = countdownTargetKm.coerceIn(5, 200),
            speedRangeStats = retainedSpeedRangeStats
        )
        lastBmsAtMillis = null
        lastCurrentA = null
        lastVoltageV = null
        lastLocationPersistAtMillis = 0L
        persist()
    }

    @Synchronized
    //MARK:更新电池
    //updateBms 使用过滤后的定位或 BMS 样本更新更新，拒绝异常时间间隔和不可信数据。
    fun updateBms(info: BmsBasicInfo, nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        if (!_state.value.isTracking || _state.value.isMileageOnly) return

        var consumedAh = _state.value.integratedConsumedAh
        var consumedWh = _state.value.integratedConsumedWh
        var speedRangeStats = _state.value.speedRangeStats
        var rangeTest = _state.value.rangeTest.copy(
            currentSocPercent = info.stateOfChargePercent,
            currentRemainingAh = info.remainingCapacityAh
        )
        val previousAt = lastBmsAtMillis
        val previousCurrent = lastCurrentA
        val previousVoltage = lastVoltageV
        if (previousAt != null && previousCurrent != null && previousVoltage != null) {
            val elapsedMs = nowMillis - previousAt
            if (elapsedMs in 250..MAX_BMS_SAMPLE_GAP_MS) {
                // 使用相邻采样点梯形积分，比只采用当前电流更能平滑电流快速变化造成的累计误差。
                val hours = elapsedMs / 3_600_000.0
                val averageCurrent = (previousCurrent + info.currentA) / 2.0
                val averageVoltage = (previousVoltage + info.totalVoltageV) / 2.0
                val dischargedAh = (-averageCurrent * hours).coerceAtLeast(0.0)
                val dischargedWh = (-averageCurrent * averageVoltage * hours).coerceAtLeast(0.0)
                consumedAh += dischargedAh
                consumedWh += dischargedWh
                val speedIndex = speedRangeStats.indexOfFirst { it.accepts(_state.value.currentSpeedKmh) }
                if (speedIndex >= 0) {
                    speedRangeStats = speedRangeStats.mapIndexed { index, stats ->
                        if (index != speedIndex) stats else stats.copy(
                            consumedAh = stats.consumedAh + dischargedAh,
                            consumedWh = stats.consumedWh + dischargedWh
                        )
                    }
                }
                if (
                    rangeTest.isActive &&
                    _state.value.currentSpeedKmh >= rangeTest.minimumSpeedKmh &&
                    _state.value.currentSpeedKmh <= rangeTest.maximumSpeedKmh
                ) {
                    rangeTest = rangeTest.copy(
                        consumedAh = rangeTest.consumedAh + dischargedAh,
                        consumedWh = rangeTest.consumedWh + dischargedWh
                    )
                }
            }
            // 超过最大采样间隔时不补算中间电量，避免断线期间用一个旧电流虚构大量消耗。
        }

        _state.value = _state.value.copy(
            currentSocPercent = info.stateOfChargePercent,
            currentRemainingAh = info.remainingCapacityAh,
            currentA = info.currentA,
            maximumRegeneration = updatedRegenerationPeak(
                existing = _state.value.maximumRegeneration,
                totalVoltageV = info.totalVoltageV,
                currentA = info.currentA,
                speedKmh = _state.value.currentSpeedKmh,
                lastLocationAtMillis = _state.value.lastLocationAtMillis,
                nowMillis = nowMillis
            ),
            integratedConsumedAh = consumedAh,
            integratedConsumedWh = consumedWh,
            rangeTest = rangeTest,
            speedRangeStats = speedRangeStats
        )
        lastBmsAtMillis = nowMillis
        lastCurrentA = info.currentA
        lastVoltageV = info.totalVoltageV
        persist()
    }

    @Synchronized
    //MARK:更新定位
    //updateLocation 使用过滤后的定位或 BMS 样本更新更新定位，拒绝异常时间间隔和不可信数据。
    fun updateLocation(
        addedDistanceMeters: Double,
        speedMetersPerSecond: Float,
        accuracyMeters: Float,
        timestampMillis: Long,
        elapsedSeconds: Double,
        speedAccuracyMetersPerSecond: Float? = null
    ) {
        ensureInitialized()
        if (!_state.value.isTracking) return
        val speedKmh = (speedMetersPerSecond * 3.6).coerceAtLeast(0.0)
        val currentTest = _state.value.rangeTest
        val bmsTracking = !_state.value.isMileageOnly
        val testAcceptsSample = bmsTracking && currentTest.isActive &&
            speedKmh >= currentTest.minimumSpeedKmh && speedKmh <= currentTest.maximumSpeedKmh &&
            addedDistanceMeters > 0.0 && elapsedSeconds in 0.0..30.0
        val updatedTest = if (testAcceptsSample) {
            currentTest.copy(
                effectiveDistanceMeters = currentTest.effectiveDistanceMeters + addedDistanceMeters,
                effectiveDurationSeconds = currentTest.effectiveDurationSeconds + elapsedSeconds
            )
        } else currentTest
        var speedRangeStats = _state.value.speedRangeStats
        if (bmsTracking && addedDistanceMeters > 0.0 && elapsedSeconds in 0.0..30.0) {
            val speedIndex = speedRangeStats.indexOfFirst { it.accepts(speedKmh) }
            if (speedIndex >= 0) {
                speedRangeStats = speedRangeStats.mapIndexed { index, stats ->
                    if (index != speedIndex) stats else stats.copy(
                        effectiveDistanceMeters = stats.effectiveDistanceMeters + addedDistanceMeters,
                        effectiveDurationSeconds = stats.effectiveDurationSeconds + elapsedSeconds
                    )
                }
            }
        }
        val updatedDistanceMeters = _state.value.distanceMeters + addedDistanceMeters.coerceAtLeast(0.0)
        val countdownReachedAt = resolveMileageCountdownReachedAt(
            existingReachedAtMillis = _state.value.mileageCountdownReachedAtMillis,
            mileageOnly = _state.value.isMileageOnly,
            distanceMeters = updatedDistanceMeters,
            targetKm = _state.value.mileageCountdownTargetKm,
            timestampMillis = timestampMillis
        )
        _state.value = _state.value.copy(
            distanceMeters = updatedDistanceMeters,
            currentSpeedKmh = speedKmh,
            locationAccuracyMeters = accuracyMeters,
            validLocationPoints = _state.value.validLocationPoints + 1,
            lastLocationAtMillis = timestampMillis,
            gpsMessage = "GPS 行程记录中",
            mileageCountdownReachedAtMillis = countdownReachedAt,
            mileageCountdownAcknowledged = if (
                countdownReachedAt != null && _state.value.mileageCountdownReachedAtMillis == null
            ) false else _state.value.mileageCountdownAcknowledged,
            rangeTest = updatedTest,
            speedRangeStats = speedRangeStats
        )
        // GPS 可能每秒多次回调；界面仍实时更新，但持久化限流到每秒一次以减少闪存写入。
        if (timestampMillis - lastLocationPersistAtMillis >= 1_000L) {
            lastLocationPersistAtMillis = timestampMillis
            persist()
        }
    }

    @Synchronized
    //MARK:清空续航样本
    //clearSpeedRangeStats 计算或控制速度续航，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun clearSpeedRangeStats() {
        ensureInitialized()
        _state.value = _state.value.copy(speedRangeStats = defaultSpeedRangeStats())
        persist()
    }


    @Synchronized
    //MARK:GPS状态
    //updateGpsStatus 使用过滤后的定位或 BMS 样本更新更新GPS状态，拒绝异常时间间隔和不可信数据。
    fun updateGpsStatus(message: String) {
        ensureInitialized()
        if (!_state.value.isTracking) return
        _state.value = _state.value.copy(gpsMessage = message, currentSpeedKmh = 0.0)
        persist()
    }

    @Synchronized
    //MARK:设置倒计时
    //setMileageCountdownTarget 计算或控制里程倒计时，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun setMileageCountdownTarget(targetKm: Int, nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        if (!_state.value.isTracking || !_state.value.isMileageOnly) return
        val target = targetKm.coerceIn(5, 200)
        // 调低目标且当前里程已经超过目标时立即进入“已到达”，无需等待下一个 GPS 点。
        val reachedAt = nowMillis.takeIf { _state.value.distanceMeters >= target * 1_000.0 }
        _state.value = _state.value.copy(
            mileageCountdownTargetKm = target,
            mileageCountdownReachedAtMillis = reachedAt,
            mileageCountdownAcknowledged = false
        )
        persist()
    }

    @Synchronized
    //MARK:确认倒计时
    //acknowledgeMileageCountdown 计算或控制里程倒计时，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun acknowledgeMileageCountdown() {
        ensureInitialized()
        if (!_state.value.isMileageOnly || _state.value.mileageCountdownReachedAtMillis == null) return
        _state.value = _state.value.copy(mileageCountdownAcknowledged = true)
        persist()
    }

    @Synchronized
    //MARK:开始续航测试
    //startRangeTest 建立续航测试的新会话基线，重置本次数据但保留允许跨行程累计的历史样本。
    fun startRangeTest(targetSpeedKmh: Int, info: BmsBasicInfo, nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        if (!_state.value.isTracking) return
        _state.value = _state.value.copy(
            rangeTest = RangeTestState(
                isActive = true,
                targetSpeedKmh = targetSpeedKmh.coerceIn(10, 100),
                startedAtMillis = nowMillis,
                startSocPercent = info.stateOfChargePercent,
                currentSocPercent = info.stateOfChargePercent,
                startRemainingAh = info.remainingCapacityAh,
                currentRemainingAh = info.remainingCapacityAh
            )
        )
        lastBmsAtMillis = nowMillis
        lastCurrentA = info.currentA
        lastVoltageV = info.totalVoltageV
        persist()
    }

    @Synchronized
    //MARK:结束续航测试
    //finishRangeTest 结束或暂停续航测试，保存已有累计值并停止继续接收实时数据。
    fun finishRangeTest(nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        val test = _state.value.rangeTest
        if (!test.isActive) return
        _state.value = _state.value.copy(
            rangeTest = test.copy(isActive = false, finishedAtMillis = nowMillis)
        )
        persist()
    }

    @Synchronized
    //MARK:结束行程
    //finish 结束当前行程，先归档完整累计数据，再停止实时速度和续航测试状态。
    fun finish(message: String = "行程已结束") {
        ensureInitialized()
        if (!_state.value.isTracking) return
        // 先归档仍含完整累计值的运行状态，再把实时状态标记为停止，避免历史记录被清零后写入。
        archiveCurrentTrip()
        _state.value = _state.value.copy(
            isTracking = false,
            currentSpeedKmh = 0.0,
            gpsMessage = message,
            rangeTest = _state.value.rangeTest.let { test ->
                if (test.isActive) test.copy(isActive = false, finishedAtMillis = System.currentTimeMillis()) else test
            }
        )
        lastBmsAtMillis = null
        lastCurrentA = null
        lastVoltageV = null
        persist()
    }

    @Synchronized
    //MARK:暂停自动行程
    //suppressUntilNextConnection 结束或暂停suppressUntilNextConnection，保存已有累计值并停止继续接收实时数据。
    fun suppressUntilNextConnection(message: String = "已手动结束行程") {
        autoStartSuppressed = true
        finish(message)
    }

    @Synchronized
    //MARK:恢复自动行程
    //resetAutoStartSuppression 计算或控制resetAutoStartSuppression，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun resetAutoStartSuppression() {
        autoStartSuppressed = false
    }

    @Synchronized
    //MARK:判断行程暂停
    //isAutoStartSuppressed 计算或控制isAutoStartSuppressed，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun isAutoStartSuppressed(): Boolean = autoStartSuppressed

    //MARK:初始化存储
    //ensureInitialized 计算或控制ensureInitialized，并保持 BMS 行程与纯 GPS 行程的数据边界。
    private fun ensureInitialized() {
        check(initialized) { "TripTracker must be initialized first" }
    }



    @Synchronized
    //MARK:读取里程
    //loadMileageSessions 读取或持久化里程会话，保证应用重启后仍能恢复有效累计结果。
    fun loadMileageSessions(): List<com.bms.jbdmanager.model.TripSessionRecord> {
        ensureInitialized()
        return mileageHistoryStore.loadSessions()
    }

    @Synchronized
    //MARK:重载行程数据
    //reloadAfterDataRestore 计算或控制数据恢复，并保持 BMS 行程与纯 GPS 行程的数据边界。
    fun reloadAfterDataRestore() {
        ensureInitialized()
        val restored = stateStore.load()
        // 备份中的“正在记录”不能直接恢复为运行态，否则系统会把旧行程当作当前行程继续累计。
        _state.value = restored.copy(
            isTracking = false,
            currentSpeedKmh = 0.0,
            gpsMessage = "备份恢复完成，连接BMS后开始新行程",
            rangeTest = restored.rangeTest.let { test ->
                if (test.isActive) test.copy(isActive = false, finishedAtMillis = System.currentTimeMillis()) else test
            }
        )
        lastBmsAtMillis = null
        lastCurrentA = null
        lastVoltageV = null
        lastLocationPersistAtMillis = 0L
        autoStartSuppressed = false
        persist()
    }

    //MARK:归档行程
    //archiveCurrentTrip 读取或持久化归档当前行程，保证应用重启后仍能恢复有效累计结果。
    private fun archiveCurrentTrip() {
        val trip = _state.value
        val startedAt = trip.startedAtMillis ?: return
        mileageHistoryStore.archiveTrip(
            startedAtMillis = startedAt,
            finishedAtMillis = System.currentTimeMillis(),
            distanceMeters = trip.distanceMeters,
            consumedAh = trip.consumedAh,
            consumedWh = trip.integratedConsumedWh,
            maximumRegeneration = trip.maximumRegeneration
        )
    }

    //MARK:保存行程
    //persist 读取或持久化persist，保证应用重启后仍能恢复有效累计结果。
    private fun persist() = stateStore.save(_state.value)
}
