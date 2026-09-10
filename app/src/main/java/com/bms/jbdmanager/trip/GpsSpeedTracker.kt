package com.bms.jbdmanager.trip

import com.bms.jbdmanager.model.GpsSpeedState

//MARK:GPS测速器
//GpsSpeedTracker 维护最近五秒速度队列，并记录从本次行程开始的最高五秒平均速度。
internal class GpsSpeedTracker {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastSampleAtMillis: Long? = null
    private var maximumAverageKmh = 0.0

    //MARK:更新状态
    //按定位时间去重速度样本，计算近五秒平均速度，并用有效五秒窗口更新本次最高平均速度。
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

    //MARK:重置状态
    //reset 清空该组件维护的临时采样和累计状态，为下一次独立会话重新建立基线。
    fun reset(baselineLocationAtMillis: Long? = null) {
        samples.clear()
        lastSampleAtMillis = baselineLocationAtMillis
        maximumAverageKmh = 0.0
    }

    //MARK:常量配置
    //近五秒平均使用 5000ms 窗口；累计最高速度前至少要求样本实际覆盖四秒。
    private companion object {
        const val WINDOW_MILLIS = 5_000L
        const val MINIMUM_MAX_SAMPLE_SPAN_MILLIS = 4_000L
    }
}
