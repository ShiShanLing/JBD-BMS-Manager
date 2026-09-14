package com.bms.jbdmanager.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

//MARK:会话记录
//TripSessionRecord 表示一条独立的行程会话记录，包含排序、统计、持久化或报告展示所需字段。
data class TripSessionRecord(
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val distanceMeters: Double,
    val consumedAh: Double,
    val consumedWh: Double,
    val maximumRegeneration: RegenerationPeak? = null,
    val category: TripCategory = TripCategory.Electric,
    val movingDurationSeconds: Double = 0.0,
    val estimatedCaloriesKcal: Double = 0.0
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
    val totalDurationSeconds: Double
        get() = ((finishedAtMillis - startedAtMillis).coerceAtLeast(0L) / 1_000.0)
    val date: LocalDate
        get() = Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

//MARK:行程类型
//区分电动车与自行车历史；旧版没有类型字段的记录统一按电动车读取。
enum class TripCategory { Electric, Bicycle }

//MARK:每日里程
//DailyMileage 将里程相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class DailyMileage(
    val date: LocalDate,
    val distanceMeters: Double,
    val consumedAh: Double = 0.0,
    val consumedWh: Double = 0.0,
    val tripCount: Int = 0,
    val totalDurationSeconds: Double = 0.0,
    val movingDurationSeconds: Double = 0.0,
    val estimatedCaloriesKcal: Double = 0.0
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
}

//MARK:里程统计周期
//MileagePeriod 枚举里程的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
enum class MileagePeriod { Day, Week, Month, Year }

//MARK:里程摘要
//MileagePeriodSummary 汇总一次里程摘要的计算或读取结果，调用方无需再从原始字段重复推导。
data class MileagePeriodSummary(
    val distanceKm: Double,
    val tripCount: Int,
    val totalDurationSeconds: Double = 0.0,
    val movingDurationSeconds: Double = 0.0,
    val estimatedCaloriesKcal: Double = 0.0
)

//MARK:里程统计区间
//MileageBucket 将里程相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class MileageBucket(
    val label: String,
    val distanceMeters: Double,
    val startDate: LocalDate,
    val endDate: LocalDate
) {
    val distanceKm: Double get() = distanceMeters / 1_000.0
}

//MARK:历史状态
//MileageHistoryState 保存里程历史状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
data class MileageHistoryState(
    val sessions: List<TripSessionRecord> = emptyList(),
    val activeTripDistanceMeters: Double = 0.0,
    val activeTripStartedAtMillis: Long? = null,
    val activeTripCategory: TripCategory = TripCategory.Electric,
    val activeTripMovingDurationSeconds: Double = 0.0,
    val activeTripCaloriesKcal: Double = 0.0
) {
    val maximumRegeneration: RegenerationPeak?
        get() = sessions.filter { it.category == TripCategory.Electric }
            .mapNotNull(TripSessionRecord::maximumRegeneration)
            .maxByOrNull(RegenerationPeak::powerW)

    //MARK:筛选类型
    //只保留指定车辆类型的历史及同类型活动行程，供图表和日历完全隔离展示。
    fun forCategory(category: TripCategory): MileageHistoryState = copy(
        sessions = sessions.filter { it.category == category },
        activeTripDistanceMeters = if (activeTripCategory == category) activeTripDistanceMeters else 0.0,
        activeTripStartedAtMillis = if (activeTripCategory == category) activeTripStartedAtMillis else null,
        activeTripMovingDurationSeconds = if (activeTripCategory == category) activeTripMovingDurationSeconds else 0.0,
        activeTripCaloriesKcal = if (activeTripCategory == category) activeTripCaloriesKcal else 0.0
    )

    //MARK:汇总每日里程
    //dailyRecords 按自然日汇总所有已完成行程；可选地把当前活动行程合并到开始日期当天。
    fun dailyRecords(includeActiveTrip: Boolean = true): List<DailyMileage> {
        val grouped = sessions.groupBy { it.date }
        val records = grouped.map { (date, trips) ->
            DailyMileage(
                date = date,
                distanceMeters = trips.sumOf { it.distanceMeters },
                consumedAh = trips.sumOf { it.consumedAh },
                consumedWh = trips.sumOf { it.consumedWh },
                tripCount = trips.size,
                totalDurationSeconds = trips.sumOf { it.totalDurationSeconds },
                movingDurationSeconds = trips.sumOf { it.movingDurationSeconds },
                estimatedCaloriesKcal = trips.sumOf { it.estimatedCaloriesKcal }
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
                tripCount = (existing?.tripCount ?: 0) + if (existing != null) 0 else 1,
                totalDurationSeconds = (existing?.totalDurationSeconds ?: 0.0) +
                    ((System.currentTimeMillis() - activeTripStartedAtMillis).coerceAtLeast(0L) / 1_000.0),
                movingDurationSeconds = (existing?.movingDurationSeconds ?: 0.0) + activeTripMovingDurationSeconds,
                estimatedCaloriesKcal = (existing?.estimatedCaloriesKcal ?: 0.0) + activeTripCaloriesKcal
            )
        }
        return records.values.sortedByDescending { it.date }
    }

    //MARK:生成里程区间
    //bucketsFor 根据日、周、月或年维度选择对应分桶算法，返回图表使用的连续里程桶。
    fun bucketsFor(period: MileagePeriod, anchor: LocalDate = LocalDate.now()): List<MileageBucket> = when (period) {
        MileagePeriod.Day -> dayBuckets(anchor)
        MileagePeriod.Week -> weekBuckets(anchor)
        MileagePeriod.Month -> monthBuckets(anchor)
        MileagePeriod.Year -> yearBuckets(anchor)
    }

    //MARK:统计周期里程
    //totalForPeriod 累加指定周期内全部里程桶的米数，得到该时间范围总里程。
    fun totalForPeriod(period: MileagePeriod, anchor: LocalDate = LocalDate.now()): Double =
        bucketsFor(period, anchor).sumOf { it.distanceMeters }

    //MARK:统计今日里程
    //todayDistanceKm 从每日汇总中查找今天并返回公里数；无记录时返回 0。
    fun todayDistanceKm(includeActiveTrip: Boolean = true): Double {
        val today = LocalDate.now()
        return dailyRecords(includeActiveTrip).firstOrNull { it.date == today }?.distanceKm ?: 0.0
    }

    //MARK:统计周期摘要
    //periodSummary 按所选日、周、月或年边界筛选记录，汇总里程和实际行程次数。
    fun periodSummary(period: MileagePeriod, anchor: LocalDate = LocalDate.now()): MileagePeriodSummary {
        val records = dailyRecords()
        return when (period) {
            MileagePeriod.Day -> {
                val record = records.firstOrNull { it.date == anchor }
                MileagePeriodSummary(
                    distanceKm = record?.distanceKm ?: 0.0,
                    tripCount = record?.tripCount ?: 0,
                    totalDurationSeconds = record?.totalDurationSeconds ?: 0.0,
                    movingDurationSeconds = record?.movingDurationSeconds ?: 0.0,
                    estimatedCaloriesKcal = record?.estimatedCaloriesKcal ?: 0.0
                )
            }
            MileagePeriod.Week -> {
                val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val end = start.plusDays(6)
                val inWeek = records.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
                MileagePeriodSummary(
                    distanceKm = inWeek.sumOf { it.distanceKm },
                    tripCount = inWeek.sumOf { it.tripCount },
                    totalDurationSeconds = inWeek.sumOf { it.totalDurationSeconds },
                    movingDurationSeconds = inWeek.sumOf { it.movingDurationSeconds },
                    estimatedCaloriesKcal = inWeek.sumOf { it.estimatedCaloriesKcal }
                )
            }
            MileagePeriod.Month -> {
                val yearMonth = YearMonth.from(anchor)
                val inMonth = records.filter { YearMonth.from(it.date) == yearMonth }
                MileagePeriodSummary(
                    distanceKm = inMonth.sumOf { it.distanceKm },
                    tripCount = inMonth.sumOf { it.tripCount },
                    totalDurationSeconds = inMonth.sumOf { it.totalDurationSeconds },
                    movingDurationSeconds = inMonth.sumOf { it.movingDurationSeconds },
                    estimatedCaloriesKcal = inMonth.sumOf { it.estimatedCaloriesKcal }
                )
            }
            MileagePeriod.Year -> {
                val inYear = records.filter { it.date.year == anchor.year }
                MileagePeriodSummary(
                    distanceKm = inYear.sumOf { it.distanceKm },
                    tripCount = inYear.sumOf { it.tripCount },
                    totalDurationSeconds = inYear.sumOf { it.totalDurationSeconds },
                    movingDurationSeconds = inYear.sumOf { it.movingDurationSeconds },
                    estimatedCaloriesKcal = inYear.sumOf { it.estimatedCaloriesKcal }
                )
            }
        }
    }

    //MARK:生成月度日历
    //calendarMonth 生成指定月份的日历信息，包含每天里程以及当月总里程。
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

    //MARK:生成每日区间
    //dayBuckets 以锚点日期为中心生成连续每日里程桶，缺少记录的日期补零。
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

    //MARK:生成每日区间
    //dayBuckets 以锚点日期为中心生成连续每日里程桶，缺少记录的日期补零。
    private fun dayBuckets(anchor: LocalDate): List<MileageBucket> = dayBuckets(YearMonth.from(anchor))

    //MARK:生成每周区间
    //weekBuckets 按周一至周日归组每日记录，生成连续周里程桶。
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

    //MARK:生成每月区间
    //monthBuckets 按自然月汇总每日记录，生成连续月份里程桶。
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

    //MARK:生成每年区间
    //yearBuckets 按自然年汇总每日记录，生成连续年份里程桶。
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
