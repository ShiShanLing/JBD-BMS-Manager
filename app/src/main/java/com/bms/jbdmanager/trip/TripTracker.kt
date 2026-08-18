package com.bms.jbdmanager.trip

import android.content.Context
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BrakeTestPhase
import com.bms.jbdmanager.model.BrakeTestState
import com.bms.jbdmanager.model.RangeTestState
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.defaultSpeedRangeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TripTracker {
    private const val PREFERENCES_NAME = "jbd_trip_tracking"
    private const val MAX_BMS_SAMPLE_GAP_MS = 15_000L

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var appContext: Context
    private var lastBmsAtMillis: Long? = null
    private var lastCurrentA: Double? = null
    private var lastVoltageV: Double? = null
    private var autoStartSuppressed = false
    private var lastLocationPersistAtMillis = 0L

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        _state.value = loadState()
        initialized = true
    }

    @Synchronized
    fun begin(info: BmsBasicInfo, nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        if (!_state.value.isTracking) {
            val retainedSpeedRangeStats = _state.value.speedRangeStats
            _state.value = TripState(
                isTracking = true,
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
    fun updateBms(info: BmsBasicInfo, nowMillis: Long = System.currentTimeMillis()) {
        ensureInitialized()
        if (!_state.value.isTracking) return

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
        }

        _state.value = _state.value.copy(
            currentSocPercent = info.stateOfChargePercent,
            currentRemainingAh = info.remainingCapacityAh,
            currentA = info.currentA,
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
        val brakeTest = updateBrakeTest(
            state = _state.value.brakeTest,
            speedKmh = speedKmh,
            timestampMillis = timestampMillis,
            speedAccuracyKmh = speedAccuracyMetersPerSecond?.times(3.6)
        )
        val currentTest = _state.value.rangeTest
        val testAcceptsSample = currentTest.isActive &&
            speedKmh >= currentTest.minimumSpeedKmh && speedKmh <= currentTest.maximumSpeedKmh &&
            addedDistanceMeters > 0.0 && elapsedSeconds in 0.0..30.0
        val updatedTest = if (testAcceptsSample) {
            currentTest.copy(
                effectiveDistanceMeters = currentTest.effectiveDistanceMeters + addedDistanceMeters,
                effectiveDurationSeconds = currentTest.effectiveDurationSeconds + elapsedSeconds
            )
        } else currentTest
        var speedRangeStats = _state.value.speedRangeStats
        if (addedDistanceMeters > 0.0 && elapsedSeconds in 0.0..30.0) {
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
        _state.value = _state.value.copy(
            distanceMeters = (_state.value.distanceMeters + addedDistanceMeters.coerceAtLeast(0.0)),
            currentSpeedKmh = speedKmh,
            locationAccuracyMeters = accuracyMeters,
            validLocationPoints = _state.value.validLocationPoints + 1,
            lastLocationAtMillis = timestampMillis,
            gpsMessage = "GPS 行程记录中",
            rangeTest = updatedTest,
            speedRangeStats = speedRangeStats,
            brakeTest = brakeTest
        )
        if (timestampMillis - lastLocationPersistAtMillis >= 1_000L) {
            lastLocationPersistAtMillis = timestampMillis
            persist()
        }
    }

    @Synchronized
    fun armBrakeTest(targetSpeedKmh: Int) {
        ensureInitialized()
        if (!_state.value.isTracking) return
        val target = targetSpeedKmh.coerceIn(10, 120)
        _state.value = _state.value.copy(
            brakeTest = BrakeTestState(
                targetSpeedKmh = target,
                phase = BrakeTestPhase.Armed,
                message = "请安全加速到 $target km/h，达到后开始制动"
            )
        )
    }

    @Synchronized
    fun cancelBrakeTest() {
        ensureInitialized()
        val target = _state.value.brakeTest.targetSpeedKmh
        _state.value = _state.value.copy(
            brakeTest = BrakeTestState(targetSpeedKmh = target, message = "测试已取消")
        )
    }

    @Synchronized
    fun clearSpeedRangeStats() {
        ensureInitialized()
        _state.value = _state.value.copy(speedRangeStats = defaultSpeedRangeStats())
        persist()
    }

    internal fun updateBrakeTest(
        state: BrakeTestState,
        speedKmh: Double,
        timestampMillis: Long,
        speedAccuracyKmh: Double?
    ): BrakeTestState {
        if (!state.isRunning) return state.copy(currentSpeedKmh = speedKmh)
        val sampleTimes = (state.recentSampleTimesMillis + timestampMillis)
            .filter { timestampMillis - it <= 3_000L }
            .takeLast(40)
        val sampleSpanSeconds = if (sampleTimes.size >= 2) {
            (sampleTimes.last() - sampleTimes.first()) / 1_000.0
        } else 0.0
        val sampleRate = if (sampleSpanSeconds > 0.0) (sampleTimes.size - 1) / sampleSpanSeconds else 0.0
        val previousAt = state.previousSampleAtMillis
        val previousSpeed = state.previousSpeedKmh
        val elapsed = previousAt?.let { (timestampMillis - it) / 1_000.0 } ?: 0.0
        val base = state.copy(
            currentSpeedKmh = speedKmh,
            sampleRateHz = sampleRate,
            speedAccuracyKmh = speedAccuracyKmh ?: state.speedAccuracyKmh,
            previousSampleAtMillis = timestampMillis,
            previousSpeedKmh = speedKmh,
            recentSampleTimesMillis = sampleTimes
        )
        if (previousAt != null && elapsed > 3.0) {
            return base.copy(phase = BrakeTestPhase.Failed, message = "GPS 采样中断，本次结果无效")
        }
        return when (state.phase) {
            BrakeTestPhase.Armed -> {
                if (speedKmh >= state.targetSpeedKmh) {
                    base.copy(phase = BrakeTestPhase.Ready, message = "已达到目标速度，请在安全路段制动")
                } else {
                    base.copy(message = "继续加速，还差 ${kotlin.math.ceil(state.targetSpeedKmh - speedKmh).toInt()} km/h")
                }
            }
            BrakeTestPhase.Ready -> {
                if (
                    previousSpeed != null && elapsed in 0.01..3.0 &&
                    previousSpeed >= state.targetSpeedKmh && speedKmh < state.targetSpeedKmh &&
                    speedKmh < previousSpeed
                ) {
                    val target = state.targetSpeedKmh.toDouble()
                    val remainingFraction = ((target - speedKmh) / (previousSpeed - speedKmh)).coerceIn(0.0, 1.0)
                    val brakingSeconds = elapsed * remainingFraction
                    val distance = ((target + speedKmh) / 2.0 / 3.6) * brakingSeconds
                    base.copy(
                        phase = BrakeTestPhase.Braking,
                        startSpeedKmh = target,
                        brakingDistanceMeters = distance,
                        brakingDurationSeconds = brakingSeconds,
                        startedAtMillis = timestampMillis - (brakingSeconds * 1_000).toLong(),
                        message = "制动中…"
                    )
                } else base
            }
            BrakeTestPhase.Braking -> {
                if (previousSpeed != null && speedKmh - previousSpeed > 5.0) {
                    base.copy(phase = BrakeTestPhase.Failed, message = "制动过程中速度明显回升，本次结果无效")
                } else if (previousSpeed != null && elapsed in 0.0..3.0) {
                    val duration = state.brakingDurationSeconds + elapsed
                    val distance = state.brakingDistanceMeters + ((previousSpeed + speedKmh) / 2.0 / 3.6) * elapsed
                    val stoppedCount = if (speedKmh <= 2.0) state.stoppedSampleCount + 1 else 0
                    if (stoppedCount >= 2) {
                        val startMps = (state.startSpeedKmh ?: state.targetSpeedKmh.toDouble()) / 3.6
                        base.copy(
                            phase = BrakeTestPhase.Complete,
                            brakingDistanceMeters = distance,
                            brakingDurationSeconds = duration,
                            averageDecelerationMps2 = if (duration > 0.0) startMps / duration else null,
                            completedAtMillis = timestampMillis,
                            stoppedSampleCount = stoppedCount,
                            message = "测试完成"
                        )
                    } else {
                        base.copy(
                            brakingDistanceMeters = distance,
                            brakingDurationSeconds = duration,
                            stoppedSampleCount = stoppedCount,
                            message = "制动中…"
                        )
                    }
                } else base
            }
            else -> base
        }
    }

    @Synchronized
    fun updateGpsStatus(message: String) {
        ensureInitialized()
        if (!_state.value.isTracking) return
        _state.value = _state.value.copy(gpsMessage = message, currentSpeedKmh = 0.0)
        persist()
    }

    @Synchronized
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
    fun finish(message: String = "行程已结束") {
        ensureInitialized()
        if (!_state.value.isTracking) return
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
    fun suppressUntilNextConnection(message: String = "已手动结束行程") {
        autoStartSuppressed = true
        finish(message)
    }

    @Synchronized
    fun resetAutoStartSuppression() {
        autoStartSuppressed = false
    }

    @Synchronized
    fun isAutoStartSuppressed(): Boolean = autoStartSuppressed

    private fun ensureInitialized() {
        check(initialized) { "TripTracker must be initialized first" }
    }

    private fun persist() {
        val value = _state.value
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("is_tracking", value.isTracking)
            .putLong("started_at", value.startedAtMillis ?: -1L)
            .putString("distance_m", value.distanceMeters.toString())
            .putInt("start_soc", value.startSocPercent ?: -1)
            .putInt("current_soc", value.currentSocPercent ?: -1)
            .putString("start_ah", value.startRemainingAh?.toString())
            .putString("current_ah", value.currentRemainingAh?.toString())
            .putString("integrated_ah", value.integratedConsumedAh.toString())
            .putString("integrated_wh", value.integratedConsumedWh.toString())
            .remove("speed_kmh")
            .putFloat("accuracy_m", value.locationAccuracyMeters ?: -1f)
            .putInt("valid_points", value.validLocationPoints)
            .putLong("last_location_at", value.lastLocationAtMillis ?: -1L)
            .putString("gps_message", value.gpsMessage)
            .putBoolean("range_active", value.rangeTest.isActive)
            .putInt("range_target_speed", value.rangeTest.targetSpeedKmh)
            .putInt("range_tolerance", value.rangeTest.speedToleranceKmh)
            .putLong("range_started_at", value.rangeTest.startedAtMillis ?: -1L)
            .putLong("range_finished_at", value.rangeTest.finishedAtMillis ?: -1L)
            .putString("range_distance_m", value.rangeTest.effectiveDistanceMeters.toString())
            .putString("range_duration_s", value.rangeTest.effectiveDurationSeconds.toString())
            .putString("range_consumed_ah", value.rangeTest.consumedAh.toString())
            .putString("range_consumed_wh", value.rangeTest.consumedWh.toString())
            .putInt("range_start_soc", value.rangeTest.startSocPercent ?: -1)
            .putInt("range_current_soc", value.rangeTest.currentSocPercent ?: -1)
            .putString("range_start_ah", value.rangeTest.startRemainingAh?.toString())
            .putString("range_current_ah", value.rangeTest.currentRemainingAh?.toString())
            .also { editor ->
                value.speedRangeStats.forEach { stats ->
                    val prefix = "speed_${stats.targetSpeedKmh}_"
                    editor.putString("${prefix}distance_m", stats.effectiveDistanceMeters.toString())
                    editor.putString("${prefix}duration_s", stats.effectiveDurationSeconds.toString())
                    editor.putString("${prefix}consumed_ah", stats.consumedAh.toString())
                    editor.putString("${prefix}consumed_wh", stats.consumedWh.toString())
                }
            }
            .apply()
    }

    private fun loadState(): TripState {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.contains("started_at")) return TripState()
        return TripState(
            isTracking = preferences.getBoolean("is_tracking", false),
            startedAtMillis = preferences.getLong("started_at", -1L).takeIf { it >= 0L },
            distanceMeters = preferences.getString("distance_m", null)?.toDoubleOrNull() ?: 0.0,
            startSocPercent = preferences.getInt("start_soc", -1).takeIf { it >= 0 },
            currentSocPercent = preferences.getInt("current_soc", -1).takeIf { it >= 0 },
            startRemainingAh = preferences.getString("start_ah", null)?.toDoubleOrNull(),
            currentRemainingAh = preferences.getString("current_ah", null)?.toDoubleOrNull(),
            integratedConsumedAh = preferences.getString("integrated_ah", null)?.toDoubleOrNull() ?: 0.0,
            integratedConsumedWh = preferences.getString("integrated_wh", null)?.toDoubleOrNull() ?: 0.0,
            currentSpeedKmh = 0.0,
            locationAccuracyMeters = preferences.getFloat("accuracy_m", -1f).takeIf { it >= 0f },
            validLocationPoints = preferences.getInt("valid_points", 0),
            lastLocationAtMillis = preferences.getLong("last_location_at", -1L).takeIf { it >= 0L },
            gpsMessage = preferences.getString("gps_message", null) ?: "等待恢复行程",
            rangeTest = RangeTestState(
                isActive = preferences.getBoolean("range_active", false),
                targetSpeedKmh = preferences.getInt("range_target_speed", 40),
                speedToleranceKmh = preferences.getInt("range_tolerance", 5),
                startedAtMillis = preferences.getLong("range_started_at", -1L).takeIf { it >= 0L },
                finishedAtMillis = preferences.getLong("range_finished_at", -1L).takeIf { it >= 0L },
                effectiveDistanceMeters = preferences.getString("range_distance_m", null)?.toDoubleOrNull() ?: 0.0,
                effectiveDurationSeconds = preferences.getString("range_duration_s", null)?.toDoubleOrNull() ?: 0.0,
                consumedAh = preferences.getString("range_consumed_ah", null)?.toDoubleOrNull() ?: 0.0,
                consumedWh = preferences.getString("range_consumed_wh", null)?.toDoubleOrNull() ?: 0.0,
                startSocPercent = preferences.getInt("range_start_soc", -1).takeIf { it >= 0 },
                currentSocPercent = preferences.getInt("range_current_soc", -1).takeIf { it >= 0 },
                startRemainingAh = preferences.getString("range_start_ah", null)?.toDoubleOrNull(),
                currentRemainingAh = preferences.getString("range_current_ah", null)?.toDoubleOrNull()
            ),
            speedRangeStats = defaultSpeedRangeStats().map { stats ->
                val prefix = "speed_${stats.targetSpeedKmh}_"
                stats.copy(
                    effectiveDistanceMeters = preferences.getString("${prefix}distance_m", null)?.toDoubleOrNull() ?: 0.0,
                    effectiveDurationSeconds = preferences.getString("${prefix}duration_s", null)?.toDoubleOrNull() ?: 0.0,
                    consumedAh = preferences.getString("${prefix}consumed_ah", null)?.toDoubleOrNull() ?: 0.0,
                    consumedWh = preferences.getString("${prefix}consumed_wh", null)?.toDoubleOrNull() ?: 0.0
                )
            }
        )
    }
}
