package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

//MARK:测试行程状态
//TripStateTest 验证 TripState 的正常流程、边界输入和需要长期保持的回归行为。
class TripStateTest {
    @Test
    //MARK:测试骑行热量
    //验证停车不累计热量，且相同速度和时长下体重越大估算热量越高。
    fun bicycleCaloriesExcludeStopsAndScaleWithWeight() {
        assertEquals(0.0, bicycleCaloriesForSample(2.0, 70.0, 10.0), 0.0001)
        val light = bicycleCaloriesForSample(22.0, 60.0, 30.0)
        val heavy = bicycleCaloriesForSample(22.0, 90.0, 30.0)
        assertEquals(4.2, light, 0.0001)
        assertEquals(6.3, heavy, 0.0001)
    }

    @Test
    //MARK:测试骑行均速
    //验证自行车均速只使用有效骑行时间，停车等待不会拉低计算结果。
    fun bicycleAverageSpeedUsesMovingDuration() {
        val trip = TripState(
            trackingMode = TripTrackingMode.Bicycle,
            distanceMeters = 10_000.0,
            bicycleMovingDurationSeconds = 1_800.0
        )
        assertEquals(20.0, trip.bicycleAverageSpeedKmh, 0.0001)
    }

    @Test
    //MARK:测试回收峰值
    //车辆正在行驶且 GPS 数据新鲜时，正电流应记录为包含电流、功率、速度和时间的回收峰值。
    fun movingPositiveCurrentCreatesRegenerationPeak() {
        val peak = updatedRegenerationPeak(
            existing = null,
            totalVoltageV = 56.0,
            currentA = 20.0,
            speedKmh = 42.5,
            lastLocationAtMillis = 9_000L,
            nowMillis = 10_000L
        )

        assertEquals(20.0, peak!!.currentA, 0.0001)
        assertEquals(1_120.0, peak.powerW, 0.0001)
        assertEquals(42.5, peak.speedKmh, 0.0001)
        assertEquals(10_000L, peak.recordedAtMillis)
    }

    @Test
    //MARK:测试回收过滤
    //静止、微小电流或过期 GPS 都不能被当作动能回收，避免把充电器电流和噪声写入历史。
    fun stationaryOrStaleSamplesDoNotCreateRegenerationPeak() {
        assertNull(updatedRegenerationPeak(null, 56.0, 20.0, 0.0, 9_000L, 10_000L))
        assertNull(updatedRegenerationPeak(null, 56.0, 0.2, 35.0, 9_000L, 10_000L))
        assertNull(updatedRegenerationPeak(null, 56.0, 20.0, 35.0, 1_000L, 10_000L))
    }

    @Test
    //MARK:测试峰值替换
    //新样本只有在回收功率更高时才覆盖旧峰值，确保一次行程始终保留真正的最高记录。
    fun regenerationPeakOnlyChangesForHigherPower() {
        val original = RegenerationPeak(18.0, 1_000.0, 40.0, 1_000L)
        val lower = updatedRegenerationPeak(original, 55.0, 17.0, 42.0, 1_900L, 2_000L)
        val higher = updatedRegenerationPeak(original, 56.0, 20.0, 38.0, 2_900L, 3_000L)

        assertEquals(original, lower)
        assertEquals(1_120.0, higher!!.powerW, 0.0001)
        assertEquals(3_000L, higher.recordedAtMillis)
    }

    @Test
    //MARK:测试续航行程
    //验证计算consumptionandremaining续航from行程样本的计算或判断结果与给定输入一致。
    fun calculatesConsumptionAndRemainingRangeFromTripSamples() {
        val trip = TripState(
            distanceMeters = 20_000.0,
            startSocPercent = 80,
            currentSocPercent = 60,
            startRemainingAh = 40.0,
            currentRemainingAh = 30.0,
            integratedConsumedAh = 9.8,
            integratedConsumedWh = 540.0
        )

        assertEquals(20, trip.socDropPercent)
        assertEquals(10.0, trip.consumedAh, 0.0001)
        assertEquals(50.0, trip.ahPer100Km!!, 0.0001)
        assertEquals(27.0, trip.whPerKm!!, 0.0001)
        assertEquals(60.0, trip.estimatedRemainingKm!!, 0.0001)
        assertEquals("较稳定", trip.estimateConfidence)
    }

    @Test
    //MARK:测试续航数据
    //验证hides续航估算untilthereisenough数据场景的关键输出，防止后续修改破坏既有行为。
    fun hidesRangeEstimateUntilThereIsEnoughData() {
        val trip = TripState(
            distanceMeters = 400.0,
            startRemainingAh = 30.0,
            currentRemainingAh = 29.95,
            integratedConsumedAh = 0.05
        )

        assertNull(trip.estimatedRemainingKm)
        assertNull(trip.ahPer100Km)
        assertEquals("采集中", trip.estimateConfidence)
    }

    @Test
    //MARK:测试里程电池
    //验证里程only行程保持里程withoutproducingbatteryestimates场景的关键输出，防止后续修改破坏既有行为。
    fun mileageOnlyTripKeepsMileageWithoutProducingBatteryEstimates() {
        val trip = TripState(
            isTracking = true,
            trackingMode = TripTrackingMode.MileageOnly,
            distanceMeters = 12_600.0
        )

        assertEquals(true, trip.isMileageOnly)
        assertEquals(12.6, trip.distanceKm, 0.0001)
        assertEquals(17.4, trip.mileageCountdownRemainingKm, 0.0001)
        assertEquals(58, trip.mileageCountdownRemainingPercent)
        assertEquals(false, trip.mileageCountdownReached)
        assertEquals(0.0, trip.consumedAh, 0.0001)
        assertNull(trip.ahPer100Km)
        assertNull(trip.whPerKm)
        assertNull(trip.estimatedRemainingKm)
        assertNull(trip.historicalRangeEstimate())
    }

    @Test
    //MARK:测试程倒计时
    //验证里程countdownremainingpercenttracks距离andstopsat零值场景的关键输出，防止后续修改破坏既有行为。
    fun mileageCountdownRemainingPercentTracksDistanceAndStopsAtZero() {
        assertEquals(
            100,
            TripState(mileageCountdownTargetKm = 40).mileageCountdownRemainingPercent
        )
        assertEquals(
            75,
            TripState(
                mileageCountdownTargetKm = 40,
                distanceMeters = 10_000.0
            ).mileageCountdownRemainingPercent
        )
        assertEquals(
            0,
            TripState(
                mileageCountdownTargetKm = 40,
                distanceMeters = 45_000.0
            ).mileageCountdownRemainingPercent
        )
    }

    @Test
    //MARK:测试程倒计时
    //验证里程countdowntriggersoncewhentargetisreached场景的关键输出，防止后续修改破坏既有行为。
    fun mileageCountdownTriggersOnceWhenTargetIsReached() {
        assertNull(
            resolveMileageCountdownReachedAt(
                existingReachedAtMillis = null,
                mileageOnly = true,
                distanceMeters = 29_999.0,
                targetKm = 30,
                timestampMillis = 100L
            )
        )
        assertEquals(
            200L,
            resolveMileageCountdownReachedAt(
                existingReachedAtMillis = null,
                mileageOnly = true,
                distanceMeters = 30_000.0,
                targetKm = 30,
                timestampMillis = 200L
            )
        )
        assertEquals(
            200L,
            resolveMileageCountdownReachedAt(
                existingReachedAtMillis = 200L,
                mileageOnly = true,
                distanceMeters = 31_000.0,
                targetKm = 30,
                timestampMillis = 300L
            )
        )
    }

    @Test
    //MARK:测试续航测试
    //验证续航测试计算onlyitsindependenteffective样本场景的关键输出，防止后续修改破坏既有行为。
    fun rangeTestCalculatesOnlyItsIndependentEffectiveSamples() {
        val test = RangeTestState(
            targetSpeedKmh = 40,
            effectiveDistanceMeters = 10_000.0,
            effectiveDurationSeconds = 900.0,
            consumedAh = 4.0,
            consumedWh = 220.0,
            currentRemainingAh = 24.0
        )

        assertEquals(35, test.minimumSpeedKmh)
        assertEquals(45, test.maximumSpeedKmh)
        assertEquals(40.0, test.averageSpeedKmh!!, 0.0001)
        assertEquals(40.0, test.ahPer100Km!!, 0.0001)
        assertEquals(22.0, test.whPerKm!!, 0.0001)
        assertEquals(60.0, test.estimatedRemainingKm!!, 0.0001)
        assertEquals("初步估算", test.confidence)
    }

    @Test
    //MARK:测试自动速度
    //验证自动速度rangesdonotoverlapfornormal样本场景的关键输出，防止后续修改破坏既有行为。
    fun automaticSpeedRangesDoNotOverlapForNormalSamples() {
        val ranges = defaultSpeedRangeStats()

        assertEquals(25, ranges.single { it.accepts(24.0) }.targetSpeedKmh)
        assertEquals(30, ranges.single { it.accepts(31.0) }.targetSpeedKmh)
        assertEquals(35, ranges.single { it.accepts(34.0) }.targetSpeedKmh)
        assertEquals(50, ranges.single { it.accepts(51.0) }.targetSpeedKmh)
        assertEquals(60, ranges.single { it.accepts(59.0) }.targetSpeedKmh)
    }

    @Test
    //MARK:测试速度续航
    //验证自动速度续航producesindependent估算场景的关键输出，防止后续修改破坏既有行为。
    fun automaticSpeedRangeProducesIndependentEstimate() {
        val stats = SpeedRangeStats(
            targetSpeedKmh = 45,
            effectiveDistanceMeters = 10_000.0,
            effectiveDurationSeconds = 800.0,
            consumedAh = 5.0,
            consumedWh = 280.0
        )

        assertEquals(45.0, stats.averageSpeedKmh!!, 0.0001)
        assertEquals(50.0, stats.ahPer100Km!!, 0.0001)
        assertEquals(28.0, stats.whPerKm!!, 0.0001)
        assertEquals(50.0, stats.estimatedRemainingKm(25.0)!!, 0.0001)
    }

    @Test
    //MARK:测试速度
    //验证historical估算使用matching速度bucketwhenavailable场景的关键输出，防止后续修改破坏既有行为。
    fun historicalEstimateUsesMatchingSpeedBucketWhenAvailable() {
        val trip = TripState(
            currentRemainingAh = 20.0,
            currentSpeedKmh = 40.2,
            speedRangeStats = defaultSpeedRangeStats().map { stats ->
                if (stats.targetSpeedKmh != 40) stats else stats.copy(
                    effectiveDistanceMeters = 12_000.0,
                    consumedAh = 4.0
                )
            }
        )

        val estimate = trip.historicalRangeEstimate()
        assertEquals(60.0, estimate!!.remainingKm, 0.0001)
        assertEquals("40km/h 档", estimate.sourceLabel)
        assertEquals("初步估算", estimate.confidence)
    }

    @Test
    //MARK:测试续航回退
    //验证historical估算fallsbacktoblendedbuckets场景的关键输出，防止后续修改破坏既有行为。
    fun historicalEstimateFallsBackToBlendedBuckets() {
        val trip = TripState(
            currentRemainingAh = 15.0,
            currentSpeedKmh = 0.0,
            speedRangeStats = defaultSpeedRangeStats().map { stats ->
                when (stats.targetSpeedKmh) {
                    30 -> stats.copy(effectiveDistanceMeters = 10_000.0, consumedAh = 4.0)
                    50 -> stats.copy(effectiveDistanceMeters = 10_000.0, consumedAh = 6.0)
                    else -> stats
                }
            }
        )

        val estimate = trip.historicalRangeEstimate()
        assertEquals(30.0, estimate!!.remainingKm, 0.0001)
        assertEquals("综合历史", estimate.sourceLabel)
        assertEquals(50.0, estimate.ahPer100Km!!, 0.0001)
    }
}
