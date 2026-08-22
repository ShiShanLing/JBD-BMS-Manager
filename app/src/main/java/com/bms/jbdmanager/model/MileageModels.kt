package com.bms.jbdmanager.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class TripSessionRecord(
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val distanceMeters: Double,
    val consumedAh: Double,
    val consumedWh: Double
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
    val date: LocalDate
        get() = Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

data class DailyMileage(
    val date: LocalDate,
    val distanceMeters: Double,
    val consumedAh: Double = 0.0,
    val consumedWh: Double = 0.0,
    val tripCount: Int = 0
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
}

enum class MileagePeriod { Day, Week, Month, Year }

data class MileagePeriodSummary(
    val distanceKm: Double,
    val tripCount: Int
)

data class MileageBucket(
    val label: String,
    val distanceMeters: Double,
    val startDate: LocalDate,
    val endDate: LocalDate
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
}

data class MileageHistoryState(
    val sessions: List<TripSessionRecord> = emptyList(),
    val activeTripDistanceMeters: Double = 0.0,
    val activeTripStartedAtMillis: Long? = null
) {
    fun dailyRecords(includeActiveTrip: Boolean = true): List<DailyMileage> {
        val grouped = sessions.groupBy { it.date }
        val records = grouped.map { (date, trips) ->
            DailyMileage(
                date = date,
                distanceMeters = trips.sumOf { it.distanceMeters },
                consumedAh = trips.sumOf { it.consumedAh },
                consumedWh = trips.sumOf { it.consumedWh },
                tripCount = trips.size
            )
        }.associateBy { it.date }.toMutableMap()

        if (includeActiveTrip && activeTripDistanceMeters > 0.0 && activeTripStartedAtMillis != null) {
            val today = Instant.ofEpochMilli(activeTripStartedAtMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val existing = records[today]
            records[today] = DailyMileage(
                date = today,
                distanceMeters = (existing?.distanceMeters ?: 0.0) + activeTripDistanceMeters,
                consumedAh = existing?.consumedAh ?: 0.0,
                consumedWh = existing?.consumedWh ?: 0.0,
                tripCount = (existing?.tripCount ?: 0) + if (existing != null) 0 else 1
            )
        }
        return records.values.sortedByDescending { it.date }
    }

    fun bucketsFor(period: MileagePeriod, anchor: LocalDate = LocalDate.now()): List<MileageBucket> = when (period) {
        MileagePeriod.Day -> dayBuckets(anchor)
        MileagePeriod.Week -> weekBuckets(anchor)
        MileagePeriod.Month -> monthBuckets(anchor)
        MileagePeriod.Year -> yearBuckets(anchor)
    }

    fun totalForPeriod(period: MileagePeriod, anchor: LocalDate = LocalDate.now()): Double =
        bucketsFor(period, anchor).sumOf { it.distanceMeters }

    fun todayDistanceKm(includeActiveTrip: Boolean = true): Double {
        val today = LocalDate.now()
        return dailyRecords(includeActiveTrip).firstOrNull { it.date == today }?.distanceKm ?: 0.0
    }

    fun periodSummary(period: MileagePeriod, anchor: LocalDate = LocalDate.now()): MileagePeriodSummary {
        val records = dailyRecords()
        return when (period) {
            MileagePeriod.Day -> {
                val record = records.firstOrNull { it.date == anchor }
                MileagePeriodSummary(
                    distanceKm = record?.distanceKm ?: 0.0,
                    tripCount = record?.tripCount ?: 0
                )
            }
            MileagePeriod.Week -> {
                val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val end = start.plusDays(6)
                val inWeek = records.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
                MileagePeriodSummary(
                    distanceKm = inWeek.sumOf { it.distanceKm },
                    tripCount = inWeek.sumOf { it.tripCount }
                )
            }
            MileagePeriod.Month -> {
                val yearMonth = YearMonth.from(anchor)
                val inMonth = records.filter { YearMonth.from(it.date) == yearMonth }
                MileagePeriodSummary(
                    distanceKm = inMonth.sumOf { it.distanceKm },
                    tripCount = inMonth.sumOf { it.tripCount }
                )
            }
            MileagePeriod.Year -> {
                val inYear = records.filter { it.date.year == anchor.year }
                MileagePeriodSummary(
                    distanceKm = inYear.sumOf { it.distanceKm },
                    tripCount = inYear.sumOf { it.tripCount }
                )
            }
        }
    }

    fun calendarMonth(yearMonth: YearMonth, includeActiveTrip: Boolean = true): List<DailyMileage?> {
        val records = dailyRecords(includeActiveTrip).associateBy { it.date }
        val firstDay = yearMonth.atDay(1)
        val leadingBlanks = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val daysInMonth = yearMonth.lengthOfMonth()
        val cells = mutableListOf<DailyMileage?>()
        repeat(leadingBlanks) { cells += null }
        for (day in 1..daysInMonth) {
            cells += records[yearMonth.atDay(day)]
                ?: DailyMileage(yearMonth.atDay(day), 0.0)
        }
        return cells
    }

    private fun dayBuckets(anchor: YearMonth): List<MileageBucket> {
        val records = dailyRecords().associateBy { it.date }
        return (1..anchor.lengthOfMonth()).map { day ->
            val date = anchor.atDay(day)
            val distance = records[date]?.distanceMeters ?: 0.0
            MileageBucket(
                label = "$day",
                distanceMeters = distance,
                startDate = date,
                endDate = date
            )
        }
    }

    private fun dayBuckets(anchor: LocalDate): List<MileageBucket> = dayBuckets(YearMonth.from(anchor))

    private fun weekBuckets(anchor: LocalDate): List<MileageBucket> {
        val records = dailyRecords().associateBy { it.date }
        val weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 7).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            val distance = records[date]?.distanceMeters ?: 0.0
            MileageBucket(
                label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA),
                distanceMeters = distance,
                startDate = date,
                endDate = date
            )
        }
    }

    private fun monthBuckets(anchor: LocalDate): List<MileageBucket> {
        val records = dailyRecords().groupBy { YearMonth.from(it.date) }
        val year = anchor.year
        return (1..12).map { month ->
            val yearMonth = YearMonth.of(year, month)
            val distance = records[yearMonth]?.sumOf { it.distanceMeters } ?: 0.0
            MileageBucket(
                label = "${month}月",
                distanceMeters = distance,
                startDate = yearMonth.atDay(1),
                endDate = yearMonth.atEndOfMonth()
            )
        }
    }

    private fun yearBuckets(anchor: LocalDate): List<MileageBucket> {
        val records = dailyRecords().groupBy { it.date.year }
        val minYear = records.keys.minOrNull()?.coerceAtMost(anchor.year) ?: anchor.year
        return (minYear..anchor.year).map { year ->
            val distance = records[year]?.sumOf { it.distanceMeters } ?: 0.0
            MileageBucket(
                label = "${year}年",
                distanceMeters = distance,
                startDate = LocalDate.of(year, 1, 1),
                endDate = LocalDate.of(year, 12, 31)
            )
        }
    }
}
