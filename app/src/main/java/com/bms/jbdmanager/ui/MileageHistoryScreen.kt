package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.DailyMileage
import com.bms.jbdmanager.model.MileageBucket
import com.bms.jbdmanager.model.MileageHistoryState
import com.bms.jbdmanager.model.MileagePeriod
import com.bms.jbdmanager.model.TripSessionRecord
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

@Composable
internal fun MileageHistoryPage(history: MileageHistoryState) {
    var period by remember { mutableStateOf(MileagePeriod.Day) }
    var calendarMonth by remember { mutableIntStateOf(YearMonth.now().monthValue) }
    var calendarYear by remember { mutableIntStateOf(YearMonth.now().year) }
    var selectedDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val selectedDate = remember(selectedDateEpoch) { LocalDate.ofEpochDay(selectedDateEpoch) }
    val yearMonth = remember(calendarYear, calendarMonth) { YearMonth.of(calendarYear, calendarMonth) }
    val today = remember { LocalDate.now() }
    val periodSummary = remember(history, period, today) { history.periodSummary(period, today) }
    val buckets = remember(history, period, yearMonth, today) {
        when (period) {
            MileagePeriod.Day -> history.bucketsFor(MileagePeriod.Day, yearMonth.atDay(1))
            else -> history.bucketsFor(period, today)
        }
    }
    val periodLabel = remember(period, today) {
        when (period) {
            MileagePeriod.Day -> "今天 ${today.monthValue}/${today.dayOfMonth}"
            MileagePeriod.Week -> {
                val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weekEnd = weekStart.plusDays(6)
                "本周 ${weekStart.monthValue}/${weekStart.dayOfMonth}-${weekEnd.monthValue}/${weekEnd.dayOfMonth}"
            }
            MileagePeriod.Month -> "本月 ${today.year}年${today.monthValue}月"
            MileagePeriod.Year -> "本年 ${today.year}年"
        }
    }
    val chartScrollTarget = remember(period, yearMonth, today, buckets) {
        when (period) {
            MileagePeriod.Day -> {
                if (yearMonth == YearMonth.from(today)) {
                    MileageChartScrollTarget.CenterIndex(today.dayOfMonth - 1)
                } else {
                    MileageChartScrollTarget.None
                }
            }
            MileagePeriod.Year -> MileageChartScrollTarget.ScrollToEnd
            else -> MileageChartScrollTarget.None
        }
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            MileageSummaryCard(
                totalKm = periodSummary.distanceKm,
                periodLabel = periodLabel,
                tripCount = periodSummary.tripCount
            )
        }
        item {
            MileagePeriodSelector(selected = period) { period = it }
        }
        item {
            MileageBarChart(
                buckets = buckets,
                accent = MaterialTheme.colorScheme.primary,
                scrollTarget = chartScrollTarget
            )
        }
        item {
            MileageCalendarCard(
                yearMonth = yearMonth,
                history = history,
                selectedDate = selectedDate,
                onPreviousMonth = {
                    val prev = yearMonth.minusMonths(1)
                    calendarYear = prev.year
                    calendarMonth = prev.monthValue
                },
                onNextMonth = {
                    val next = yearMonth.plusMonths(1)
                    calendarYear = next.year
                    calendarMonth = next.monthValue
                },
                onSelectDate = { selectedDateEpoch = it.toEpochDay() }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MileageSummaryCard(totalKm: Double, periodLabel: String, tripCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("骑行里程", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "${compactNumber(totalKm)} km",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
                lineHeight = 34.sp
            )
            Text(
                "$periodLabel · 累计 $tripCount 次行程",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MileagePeriodSelector(selected: MileagePeriod, onSelect: (MileagePeriod) -> Unit) {
    val labels = listOf(
        MileagePeriod.Day to "日",
        MileagePeriod.Week to "周",
        MileagePeriod.Month to "月",
        MileagePeriod.Year to "年"
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { (period, label) ->
            val active = period == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(period) },
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun MileageBarChart(
    buckets: List<MileageBucket>,
    accent: Color,
    scrollTarget: MileageChartScrollTarget = MileageChartScrollTarget.None
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("里程趋势", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "柱顶数字为各时段里程，可左右滑动查看全部",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (buckets.isEmpty()) {
                Text("暂无里程数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                return@Column
            }
            val maxKm = buckets.maxOfOrNull { it.distanceKm }?.coerceAtLeast(0.1) ?: 1.0
            val barSlotWidth = when {
                maxKm >= 100 -> 40.dp
                buckets.size > 20 -> 32.dp
                buckets.size > 12 -> 36.dp
                buckets.size > 8 -> 40.dp
                else -> 44.dp
            }
            val chartHeight = 144.dp
            val scrollState = rememberScrollState()
            val barSpacing = 4.dp
            val rowPadding = 4.dp
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val viewportWidth = maxWidth
                val density = LocalDensity.current
                LaunchedEffect(buckets.size, scrollTarget, viewportWidth, barSlotWidth, scrollState.maxValue) {
                    if (buckets.isEmpty()) return@LaunchedEffect
                    when (val target = scrollTarget) {
                        is MileageChartScrollTarget.CenterIndex -> {
                            val index = target.index.coerceIn(0, buckets.lastIndex)
                            with(density) {
                                val itemStride = (barSlotWidth + barSpacing).toPx()
                                val paddingPx = rowPadding.toPx()
                                val itemCenter = paddingPx + index * itemStride + barSlotWidth.toPx() / 2f
                                val viewportPx = viewportWidth.toPx()
                                val scrollX = (itemCenter - viewportPx / 2f)
                                    .coerceIn(0f, scrollState.maxValue.toFloat())
                                scrollState.scrollTo(scrollX.toInt())
                            }
                        }
                        MileageChartScrollTarget.ScrollToEnd -> {
                            if (scrollState.maxValue > 0) {
                                scrollState.scrollTo(scrollState.maxValue)
                            }
                        }
                        MileageChartScrollTarget.None -> Unit
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = rowPadding),
                    horizontalArrangement = Arrangement.spacedBy(barSpacing),
                    verticalAlignment = Alignment.Bottom
                ) {
                    buckets.forEach { bucket ->
                        MileageBarColumn(
                            bucket = bucket,
                            maxKm = maxKm,
                            accent = accent,
                            slotWidth = barSlotWidth,
                            chartHeight = chartHeight
                        )
                    }
                }
            }
        }
    }
}

private sealed interface MileageChartScrollTarget {
    data class CenterIndex(val index: Int) : MileageChartScrollTarget
    data object ScrollToEnd : MileageChartScrollTarget
    data object None : MileageChartScrollTarget
}

@Composable
private fun MileageBarColumn(
    bucket: MileageBucket,
    maxKm: Double,
    accent: Color,
    slotWidth: Dp,
    chartHeight: Dp
) {
    val valueAreaHeight = 16.dp
    val labelAreaHeight = 14.dp
    val barAreaHeight = chartHeight - valueAreaHeight - labelAreaHeight - 4.dp
    val ratio = (bucket.distanceKm / maxKm).coerceIn(0.0, 1.0).toFloat()
    val hasData = bucket.distanceKm > 0.0
    Column(
        modifier = Modifier.width(slotWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(valueAreaHeight),
            contentAlignment = Alignment.Center
        ) {
            if (hasData) {
                Text(
                    text = compactNumber(bucket.distanceKm, 1),
                    color = accent,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentWidth(unbounded = true)
                )
            }
        }
        Box(
            modifier = Modifier
                .width(slotWidth.coerceAtMost(32.dp))
                .height(barAreaHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        if (hasData) {
                            (barAreaHeight * ratio).coerceAtLeast(4.dp)
                        } else {
                            0.dp
                        }
                    )
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (hasData) accent else accent.copy(alpha = 0.15f))
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            bucket.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(labelAreaHeight)
        )
    }
}

@Composable
private fun MileageCalendarCard(
    yearMonth: YearMonth,
    history: MileageHistoryState,
    selectedDate: LocalDate,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val cells = remember(history, yearMonth) { history.calendarMonth(yearMonth) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "◀",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onPreviousMonth)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${yearMonth.year}年${yearMonth.monthValue}月",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "▶",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onNextMonth)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val rows = cells.chunked(7)
            rows.forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { cell ->
                        MileageCalendarCell(
                            record = cell,
                            selected = cell?.date == selectedDate,
                            onClick = cell?.date?.let { date -> ({ onSelectDate(date) }) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(7 - week.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private val CalendarDataDateColor = Color(0xFF141414)
private val CalendarDataKmColor = Color(0xFF505050)

@Composable
private fun MileageCalendarCell(
    record: DailyMileage?,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (record == null) {
        Spacer(modifier.height(48.dp))
        return
    }
    val hasData = record.distanceKm > 0.0
    val bgColor = if (hasData) {
        Color.White.copy(alpha = 0.95f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)
    }
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        record.date == LocalDate.now() -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp, horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${record.date.dayOfMonth}",
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected || record.date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
            color = if (hasData) CalendarDataDateColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasData) {
            Text(
                compactNumber(record.distanceKm, 1),
                fontSize = 8.sp,
                lineHeight = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = CalendarDataKmColor
            )
        }
    }
}

@Preview(name = "里程统计", showBackground = true, widthDp = 392, heightDp = 850)
@Composable
private fun MileageHistoryPreview() {
    JbdBmsTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val today = LocalDate.now()
            val sessions = (0 until 20).flatMap { offset ->
                val date = today.minusDays(offset.toLong())
                if (offset % 3 == 0) {
                    listOf(
                        TripSessionRecord(
                            startedAtMillis = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            finishedAtMillis = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000,
                            distanceMeters = (5 + offset % 7) * 1_000.0,
                            consumedAh = 1.2 + offset * 0.1,
                            consumedWh = 60.0 + offset * 2
                        )
                    )
                } else emptyList()
            }
            MileageHistoryPage(
                history = MileageHistoryState(
                    sessions = sessions,
                    activeTripDistanceMeters = 2_400.0,
                    activeTripStartedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }
}
