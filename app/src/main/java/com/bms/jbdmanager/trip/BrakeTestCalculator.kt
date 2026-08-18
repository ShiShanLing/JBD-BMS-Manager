package com.bms.jbdmanager.trip

import com.bms.jbdmanager.model.BrakeTestPhase
import com.bms.jbdmanager.model.BrakeTestState

internal object BrakeTestCalculator {
    fun update(
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

}
