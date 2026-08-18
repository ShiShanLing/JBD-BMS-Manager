package com.bms.jbdmanager.trip

import com.bms.jbdmanager.model.GpsSpeedState

internal class GpsSpeedTracker {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastSampleAtMillis: Long? = null
    private var maximumAverageKmh = 0.0

    fun update(currentKmh: Double, locationAtMillis: Long?): GpsSpeedState {
        val safeCurrent = currentKmh.coerceAtLeast(0.0)
        if (locationAtMillis != null && locationAtMillis != lastSampleAtMillis) {
            lastSampleAtMillis = locationAtMillis
            samples.addLast(locationAtMillis to safeCurrent)
            while (samples.isNotEmpty() && locationAtMillis - samples.first().first > WINDOW_MILLIS) {
                samples.removeFirst()
            }
        }
        if (samples.isEmpty()) return GpsSpeedState()
        val average = samples.map { it.second }.average().takeUnless { it.isNaN() } ?: 0.0
        val sampleSpanMillis = samples.last().first - samples.first().first
        if (sampleSpanMillis >= MINIMUM_MAX_SAMPLE_SPAN_MILLIS) {
            maximumAverageKmh = maxOf(maximumAverageKmh, average)
        }
        return GpsSpeedState(safeCurrent, average, maximumAverageKmh)
    }

    fun reset(baselineLocationAtMillis: Long? = null) {
        samples.clear()
        lastSampleAtMillis = baselineLocationAtMillis
        maximumAverageKmh = 0.0
    }

    private companion object {
        const val WINDOW_MILLIS = 5_000L
        const val MINIMUM_MAX_SAMPLE_SPAN_MILLIS = 4_000L
    }
}
