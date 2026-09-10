package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

//MARK:测试历史状态
//MileageHistoryStateTest 验证 MileageHistoryState 的正常流程、边界输入和需要长期保持的回归行为。
class MileageHistoryStateTest {
    private val zone = ZoneId.systemDefault()

    //MARK:处理会话
    //构造指定日期和距离的已完成行程，便于验证日历及周期汇总。
    private fun session(
        date: LocalDate,
        distanceKm: Double,
        hour: Int = 10
    ): TripSessionRecord {
        val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return TripSessionRecord(
            startedAtMillis = start,
            finishedAtMillis = start + 3_600_000,
            distanceMeters = distanceKm * 1_000.0,
            consumedAh = distanceKm * 0.2,
            consumedWh = distanceKm * 10.0
        )
    }

    @Test
    //MARK:测试历史回收
    //历史最大回收应从所有已归档行程中按功率选择，供离线页面持续展示最高记录。
    fun maximumRegenerationUsesHighestArchivedPower() {
        val today = LocalDate.of(2026, 9, 10)
        val lower = session(today, 10.0).copy(
            maximumRegeneration = RegenerationPeak(15.0, 840.0, 35.0, 1_000L)
        )
        val higher = session(today.minusDays(1), 12.0).copy(
            maximumRegeneration = RegenerationPeak(21.0, 1_176.0, 42.0, 2_000L)
        )

        assertEquals(higher.maximumRegeneration, MileageHistoryState(listOf(lower, higher)).maximumRegeneration)
    }

    @Test
    //MARK:测试每日汇总
    //验证每日记录groupssessionsby启动date场景的关键输出，防止后续修改破坏既有行为。
    fun dailyRecords_groupsSessionsByStartDate() {
        val today = LocalDate.of(2026, 8, 22)
        val history = MileageHistoryState(
            sessions = listOf(
                session(today, 5.0),
                session(today, 3.0),
                session(today.minusDays(1), 8.0)
            )
        )
        val records = history.dailyRecords(includeActiveTrip = false)
        val todayRecord = records.first { it.date == today }
        assertEquals(8_000.0, todayRecord.distanceMeters, 0.1)
        assertEquals(2, todayRecord.tripCount)
    }

    @Test
    //MARK:测试行程
    //验证每日记录includes活动行程距离场景的关键输出，防止后续修改破坏既有行为。
    fun dailyRecords_includesActiveTripDistance() {
        val today = LocalDate.of(2026, 8, 22)
        val startedAt = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val history = MileageHistoryState(
            sessions = listOf(session(today, 2.0)),
            activeTripDistanceMeters = 1_500.0,
            activeTripStartedAtMillis = startedAt
        )
        val todayRecord = history.dailyRecords().first { it.date == today }
        assertEquals(3_500.0, todayRecord.distanceMeters, 0.1)
    }

    @Test
    //MARK:测试当前
    //验证weekbuckets开始frommondayof电流week场景的关键输出，防止后续修改破坏既有行为。
    fun weekBuckets_startsFromMondayOfCurrentWeek() {
        val anchor = LocalDate.of(2026, 8, 22)
        val history = MileageHistoryState(
            sessions = listOf(
                session(LocalDate.of(2026, 8, 18), 5.0),
                session(LocalDate.of(2026, 8, 17), 99.0)
            )
        )
        val buckets = history.bucketsFor(MileagePeriod.Week, anchor)
        assertEquals(7, buckets.size)
        assertEquals(LocalDate.of(2026, 8, 17), buckets.first().startDate)
        assertEquals(99_000.0, buckets.first().distanceMeters, 0.1)
        assertEquals(5_000.0, buckets.first { it.startDate == LocalDate.of(2026, 8, 18) }.distanceMeters, 0.1)
    }

    @Test
    //MARK:测试当前
    //验证monthbuckets返回twelvemonthsfor电流year场景的关键输出，防止后续修改破坏既有行为。
    fun monthBuckets_returnsTwelveMonthsForCurrentYear() {
        val anchor = LocalDate.of(2026, 8, 22)
        val history = MileageHistoryState(
            sessions = listOf(
                session(LocalDate.of(2026, 8, 10), 12.0),
                session(LocalDate.of(2026, 7, 5), 6.0),
                session(LocalDate.of(2025, 12, 1), 20.0)
            )
        )
        val buckets = history.bucketsFor(MileagePeriod.Month, anchor)
        assertEquals(12, buckets.size)
        assertEquals("8月", buckets[7].label)
        assertEquals(12_000.0, buckets[7].distanceMeters, 0.1)
        assertEquals(6_000.0, buckets[6].distanceMeters, 0.1)
        assertEquals(0.0, buckets[0].distanceMeters, 0.1)
    }

    @Test
    //MARK:测试当前
    //验证yearbuckets返回allyearsthrough电流year场景的关键输出，防止后续修改破坏既有行为。
    fun yearBuckets_returnsAllYearsThroughCurrentYear() {
        val anchor = LocalDate.of(2026, 8, 22)
        val history = MileageHistoryState(
            sessions = listOf(
                session(LocalDate.of(2026, 8, 10), 12.0),
                session(LocalDate.of(2026, 7, 5), 6.0),
                session(LocalDate.of(2024, 12, 1), 20.0)
            )
        )
        val buckets = history.bucketsFor(MileagePeriod.Year, anchor)
        assertEquals(listOf("2024年", "2025年", "2026年"), buckets.map { it.label })
        assertEquals(20_000.0, buckets[0].distanceMeters, 0.1)
        assertEquals(0.0, buckets[1].distanceMeters, 0.1)
        assertEquals(18_000.0, buckets[2].distanceMeters, 0.1)
    }

    @Test
    //MARK:测试摘要
    //验证periodsummary返回今日monthandyeartotals场景的关键输出，防止后续修改破坏既有行为。
    fun periodSummary_returnsTodayMonthAndYearTotals() {
        val today = LocalDate.of(2026, 8, 22)
        val history = MileageHistoryState(
            sessions = listOf(
                session(today, 5.0),
                session(today, 3.0),
                session(today.minusDays(1), 8.0),
                session(LocalDate.of(2026, 7, 5), 6.0),
                session(LocalDate.of(2025, 12, 1), 20.0)
            )
        )
        assertEquals(8.0, history.periodSummary(MileagePeriod.Day, today).distanceKm, 0.1)
        assertEquals(2, history.periodSummary(MileagePeriod.Day, today).tripCount)
        assertEquals(16.0, history.periodSummary(MileagePeriod.Month, today).distanceKm, 0.1)
        assertEquals(22.0, history.periodSummary(MileagePeriod.Year, today).distanceKm, 0.1)
    }

    @Test
    //MARK:测试日历
    //验证calendarmonthpadsleadingdays场景的关键输出，防止后续修改破坏既有行为。
    fun calendarMonth_padsLeadingDays() {
        val yearMonth = java.time.YearMonth.of(2026, 8)
        val history = MileageHistoryState(
            sessions = listOf(session(LocalDate.of(2026, 8, 22), 4.0))
        )
        val cells = history.calendarMonth(yearMonth)
        assertTrue(cells.size >= 31)
        assertEquals(null, cells.first())
    }

    @Test
    //MARK:测试今日里程
    //验证今日距离km返回今日total场景的关键输出，防止后续修改破坏既有行为。
    fun todayDistanceKm_returnsTodayTotal() {
        val today = LocalDate.now()
        val history = MileageHistoryState(
            sessions = listOf(
                session(today, 6.0),
                session(today.minusDays(1), 10.0)
            ),
            activeTripDistanceMeters = 1_000.0,
            activeTripStartedAtMillis = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        )
        assertEquals(7.0, history.todayDistanceKm(), 0.1)
    }

    @Test
    //MARK:测试里程数据
    //验证GPSonly会话stillcontributes里程without电量数据场景的关键输出，防止后续修改破坏既有行为。
    fun gpsOnlySessionStillContributesMileageWithoutEnergyData() {
        val today = LocalDate.now()
        val startedAt = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val history = MileageHistoryState(
            sessions = listOf(
                TripSessionRecord(
                    startedAtMillis = startedAt,
                    finishedAtMillis = startedAt + 1_800_000,
                    distanceMeters = 12_600.0,
                    consumedAh = 0.0,
                    consumedWh = 0.0
                )
            )
        )

        val todayRecord = history.dailyRecords(includeActiveTrip = false).single()
        assertEquals(12.6, todayRecord.distanceKm, 0.0001)
        assertEquals(0.0, todayRecord.consumedAh, 0.0001)
        assertEquals(0.0, todayRecord.consumedWh, 0.0001)
        assertEquals(1, todayRecord.tripCount)
    }
}
