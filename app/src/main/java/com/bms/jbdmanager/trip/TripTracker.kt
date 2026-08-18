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
    private const val MAX_BMS_SAMPLE_GAP_MS = 15_000L

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var stateStore: TripStateStore
    private var lastBmsAtMillis: Long? = null
    private var lastCurrentA: Double? = null
    private var lastVoltageV: Double? = null
    private var autoStartSuppressed = false
    private var lastLocationPersistAtMillis = 0L

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        stateStore = TripStateStore(context.applicationContext)
        _state.value = stateStore.load()
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
    ): BrakeTestState = BrakeTestCalculator.update(
        state = state,
        speedKmh = speedKmh,
        timestampMillis = timestampMillis,
        speedAccuracyKmh = speedAccuracyKmh
    )

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



    private fun persist() = stateStore.save(_state.value)
}
