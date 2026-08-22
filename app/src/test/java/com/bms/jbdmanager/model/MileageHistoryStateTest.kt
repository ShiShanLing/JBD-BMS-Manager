package com.bms.jbdmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MileageHistoryStateTest {
    private val zone = ZoneId.systemDefault()

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
        assertEquals(LocalDate.of(2026, 8, 18), buckets.first().startDate)
        assertEquals(5_000.0, buckets.first().distanceMeters, 0.1)
        assertEquals(0.0, buckets.sumOf { if (it.startDate == LocalDate.of(2026, 8, 17)) it.distanceMeters else 0.0 }, 0.1)
    }

    @Test
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
        assertEquals(24.0, history.periodSummary(MileagePeriod.Year, today).distanceKm, 0.1)
    }

    @Test
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
}
