package com.bms.jbdmanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.BatteryTrendRange
import com.bms.jbdmanager.model.FullChargeDeltaDirection
import com.bms.jbdmanager.model.FullChargeDeltaSample
import com.bms.jbdmanager.model.evaluateFullChargeDeltaTrend
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class DeltaChartStyle(val label: String) {
    Line("折线图"),
    Bar("柱状图")
}

@Composable
internal fun FullChargeStatsDestination(
    state: BmsUiState,
    onLoadBatteryTrend: (BatteryTrendRange) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        HistoryDetailHeader("满充统计", onBack)
        FullChargeDeltaStatsPage(
            samples = state.batteryTrend.fullChargeDeltas,
            onLoad = { onLoadBatteryTrend(state.batteryTrend.range) }
        )
    }
}

@Composable
internal fun FullChargeDeltaStatsPage(
    samples: List<FullChargeDeltaSample>,
    onLoad: () -> Unit
) {
    val trend = remember(samples) { evaluateFullChargeDeltaTrend(samples) }
    var chartStyle by rememberSaveable { mutableStateOf(DeltaChartStyle.Line) }
    LaunchedEffect(Unit) { onLoad() }
    val accent = when (trend.direction) {
        FullChargeDeltaDirection.Improving -> MaterialTheme.colorScheme.primary
        FullChargeDeltaDirection.Stable -> MaterialTheme.colorScheme.onSurfaceVariant
        FullChargeDeltaDirection.Worsening -> Color(0xFFFF8A3D)
        FullChargeDeltaDirection.Insufficient -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val values = trend.samples.map { it.cellDeltaMv.toDouble() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("满充压差统计", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "连接且接近满充时记录 · 压差变小表示一致性向好",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                        trend.latestDeltaMv?.let { latest ->
                            Text(
                                "$latest mV",
                                color = accent,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (trend.direction) {
                            FullChargeDeltaDirection.Improving -> "向好"
                            FullChargeDeltaDirection.Stable -> "基本稳定"
                            FullChargeDeltaDirection.Worsening -> "变差"
                            FullChargeDeltaDirection.Insufficient -> "数据不足"
                        },
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        trend.summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    ChartStyleSelector(chartStyle) { chartStyle = it }
                    Spacer(Modifier.height(10.dp))
                    when {
                        values.isEmpty() -> Box(
                            Modifier.fillMaxWidth().height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "尚无满充记录。连接且接近满电后会自动统计。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        values.size == 1 -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatsMetric("SOC", "${trend.samples.first().socPercent}%")
                                StatsMetric("压差", "${trend.samples.first().cellDeltaMv} mV")
                                StatsMetric(
                                    "剩余",
                                    trend.samples.first().remainingCapacityAh?.let { "${compactAh(it)} Ah" } ?: "--"
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "再记录一次满充后即可形成图表",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatsMetric("首次", "${trend.baselineDeltaMv ?: "--"} mV")
                                StatsMetric(
                                    "变化",
                                    trend.changeMv?.let { value ->
                                        "${if (value > 0) "+" else ""}${compactAh(value)} mV"
                                    } ?: "--"
                                )
                                StatsMetric("最新", "${trend.latestDeltaMv ?: "--"} mV")
                            }
                            Spacer(Modifier.height(10.dp))
                            ScrollableStatsChart(values = values, color = accent, style = chartStyle)
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    formatStatsDate(trend.samples.first().capturedAtMillis),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${trend.samples.size}次",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    formatStatsDate(trend.samples.last().capturedAtMillis),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartStyleSelector(selected: DeltaChartStyle, onSelect: (DeltaChartStyle) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeltaChartStyle.entries.forEach { style ->
            val active = style == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(style) },
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    style.label,
                    modifier = Modifier.padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun StatsMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ScrollableStatsChart(
    values: List<Double>,
    color: Color,
    style: DeltaChartStyle
) {
    val slotWidth = 28.dp
    val scrollState = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, slotWidth * values.size.coerceAtLeast(2))
        val canScroll = chartWidth > maxWidth
        LaunchedEffect(values.size, chartWidth, maxWidth, scrollState.maxValue) {
            if (canScroll && scrollState.maxValue > 0) {
                scrollState.scrollTo(scrollState.maxValue)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
            ) {
                val chartModifier = Modifier.width(chartWidth).height(220.dp)
                if (style == DeltaChartStyle.Line) {
                    StatsLineChart(values = values, color = color, modifier = chartModifier)
                } else {
                    StatsBarChart(values = values, color = color, modifier = chartModifier)
                }
            }
            if (canScroll) {
                Text(
                    "可左右滑动查看全部记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatsLineChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp)
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Canvas(modifier) {
        repeat(5) { index ->
            val y = size.height * index / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val min = values.min() - ((values.max() - values.min()) * 0.08).coerceAtLeast(0.01)
        val max = values.max() + ((values.max() - values.min()) * 0.08).coerceAtLeast(0.01)
        val range = (max - min).coerceAtLeast(0.01)
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f else size.width * index / (values.size - 1).toFloat()
            val y = size.height - ((value - min) / range * size.height).toFloat()
            val point = Offset(x, y)
            previous?.let { drawLine(color, it, point, strokeWidth = 5f, cap = StrokeCap.Round) }
            drawCircle(color, radius = 6f, center = point)
            previous = point
        }
    }
}

@Composable
private fun StatsBarChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp)
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Canvas(modifier) {
        repeat(5) { index ->
            val y = size.height * index / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val min = 0.0
        val max = (values.max() * 1.12).coerceAtLeast(1.0)
        val range = (max - min).coerceAtLeast(0.01)
        val slot = size.width / values.size.coerceAtLeast(1)
        val barWidth = (slot * 0.58f).coerceAtLeast(8f).coerceAtMost(36f)
        values.forEachIndexed { index, value ->
            val height = ((value - min) / range * size.height).toFloat().coerceAtLeast(4f)
            val x = slot * index + (slot - barWidth) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(8f, 8f)
            )
        }
    }
}

private fun compactAh(value: Double): String {
    val formatted = "%.2f".format(Locale.US, value)
    return formatted.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
}

private val statsDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatStatsDate(timestamp: Long): String =
    statsDateFormatter.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

@Preview(name = "满充统计", showBackground = true, widthDp = 392, heightDp = 850)
@Composable
private fun FullChargeDeltaStatsPreview() {
    JbdBmsTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FullChargeDeltaStatsPage(
                samples = demoBmsState().batteryTrend.fullChargeDeltas,
                onLoad = {}
            )
        }
    }
}
