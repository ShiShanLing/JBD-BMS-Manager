package com.bms.jbdmanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.BatteryTrendRange
import com.bms.jbdmanager.model.BatteryTrendState
import com.bms.jbdmanager.model.FullChargeFingerprint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

//MARK:趋势指标
//TrendMetric 枚举趋势指标的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
private enum class TrendMetric(val label: String, val unit: String) {
    Voltage("总压", "V"),
    Current("电流", "A"),
    Soc("SOC", "%"),
    Temperature("最高温", "°C"),
    Delta("压差", "mV"),
    MinimumCell("最低单体", "mV")
}

@Composable
//MARK:电池趋势页
//BatteryTrendPage 组织电池趋势页面的完整页面结构，组合内容区和操作入口，并把事件交给状态持有层。
internal fun BatteryTrendPage(
    trend: BatteryTrendState,
    onLoadRange: (BatteryTrendRange) -> Unit
) {
    var metric by rememberSaveable { mutableStateOf(TrendMetric.Voltage) }
    LaunchedEffect(Unit) { onLoadRange(trend.range) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { TrendRangeSelector(trend.range, onLoadRange) }
        item { TrendMetricSelector(metric) { metric = it } }
        item {
            TrendChartCard(
                trend = trend,
                metric = metric
            )
        }
        item { FullChargeComparisonCard(trend.fullChargeFingerprints) }
        item {
            Text(
                "连接期间每10秒记录；近7天保留详细数据，7～30天自动汇总。每日摘要和满充单体指纹长期保留，用于以后比较电池变化。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
//MARK:满充对比卡
//FullChargeComparisonCard 绘制满充充电卡片卡片，将同一主题的标题、关键数值和辅助信息组合展示。
private fun FullChargeComparisonCard(fingerprints: List<FullChargeFingerprint>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("满充单体长期对比", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(3.dp))
            when {
                fingerprints.isEmpty() -> Text(
                    "电池达到98%以上后会自动建立第一份满充单体记录。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                fingerprints.size == 1 -> {
                    val only = fingerprints.first()
                    Text(
                        "已保存 ${formatFingerprintDate(only.capturedAtMillis)} 的基准，下一次满充后即可比较。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Text(
                        "总压 ${compact(only.totalVoltageV, 2)}V · 压差 ${only.cellDeltaMv ?: 0}mV",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                else -> {
                    val first = fingerprints.first()
                    val latest = fingerprints.last()
                    Text(
                        "${formatFingerprintDate(first.capturedAtMillis)} → ${formatFingerprintDate(latest.capturedAtMillis)} · 共${fingerprints.size}次",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    val count = minOf(first.cellVoltagesMv.size, latest.cellVoltagesMv.size)
                    (0 until count).chunked(4).forEach { rowIndices ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            rowIndices.forEach { index ->
                                val change = latest.cellVoltagesMv[index] - first.cellVoltagesMv[index]
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("${index + 1}串", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            "${if (change > 0) "+" else ""}$change mV",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                change <= -40 -> MaterialTheme.colorScheme.error
                                                change <= -20 -> Color(0xFFFFA726)
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }
                                }
                            }
                            repeat(4 - rowIndices.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    Text(
                        "对比值会受满充终止电压、温度和静置时间影响，应结合多次记录判断，不能单独当作容量健康度。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
//MARK:趋势范围选择
//TrendRangeSelector 展示趋势续航的可选入口，突出当前项并通过回调上报切换结果。
private fun TrendRangeSelector(selected: BatteryTrendRange, onSelect: (BatteryTrendRange) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BatteryTrendRange.entries.forEach { range ->
            val active = range == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(range) },
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    range.label,
                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 1.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
//MARK:趋势指标选择
//TrendMetricSelector 展示趋势指标的可选入口，突出当前项并通过回调上报切换结果。
private fun TrendMetricSelector(selected: TrendMetric, onSelect: (TrendMetric) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TrendMetric.entries.forEach { metric ->
            val active = metric == selected
            Surface(
                modifier = Modifier.clickable { onSelect(metric) },
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                tonalElevation = if (active) 1.dp else 0.dp
            ) {
                Text(
                    metric.label,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
//MARK:趋势图表卡
//TrendChartCard 绘制趋势图表卡片卡片，将同一主题的标题、关键数值和辅助信息组合展示。
private fun TrendChartCard(trend: BatteryTrendState, metric: TrendMetric) {
    val samples = remember(trend.points, metric) {
        trend.points.mapNotNull { point -> metric.valueOf(point)?.let { point.timestampMillis to it } }
    }
    val accent = metric.color()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${metric.label}趋势", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "${trend.range.label} · ${samples.size} 个图表点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                samples.lastOrNull()?.let { (_, value) ->
                    Text(
                        metric.format(value),
                        color = accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                trend.isLoading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                samples.size < 2 -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(
                        trend.message ?: "至少记录两个数据点后才会形成曲线",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    val values = samples.map { it.second }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TrendStat("最低", metric.format(values.min()))
                        TrendStat("平均", metric.format(values.average()))
                        TrendStat("最高", metric.format(values.max()))
                    }
                    Spacer(Modifier.height(8.dp))
                    TrendLineChart(values = values, color = accent)
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTrendTime(samples.first().first, trend.range), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTrendTime(samples.last().first, trend.range), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
//MARK:趋势统计项
//TrendStat 绘制趋势组件，并根据参数决定文案、数值、颜色及交互状态。
private fun TrendStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
//MARK:趋势折线图
//TrendLineChart 根据趋势图表样本计算坐标和比例，并绘制趋势、柱形或进度信息。
private fun TrendLineChart(values: List<Double>, color: Color) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        repeat(5) { index ->
            val y = size.height * index / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val rawMin = values.min()
        val rawMax = values.max()
        val padding = ((rawMax - rawMin) * 0.08).coerceAtLeast(0.01)
        val min = rawMin - padding
        val max = rawMax + padding
        val range = (max - min).coerceAtLeast(0.01)
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) 0f else size.width * index / (values.size - 1).toFloat()
            val y = size.height - ((value - min) / range * size.height).toFloat()
            val point = Offset(x, y)
            previous?.let { drawLine(color, it, point, strokeWidth = 4f, cap = StrokeCap.Round) }
            previous = point
        }
    }
}

//MARK:读取指标值
//valueOf 绘制值组件，并根据参数决定文案、数值、颜色及交互状态。
private fun TrendMetric.valueOf(point: BatteryTrendPoint): Double? = when (this) {
    TrendMetric.Voltage -> point.totalVoltageV
    TrendMetric.Current -> point.currentA
    TrendMetric.Soc -> point.socPercent
    TrendMetric.Temperature -> point.maximumTemperatureC
    TrendMetric.Delta -> point.cellDeltaMv
    TrendMetric.MinimumCell -> point.minimumCellMv
}

@Composable
//MARK:趋势指标颜色
//color 按color等级或数值范围选择语义颜色，区分正常、警告和危险状态。
private fun TrendMetric.color(): Color = when (this) {
    TrendMetric.Current -> MaterialTheme.colorScheme.tertiary
    TrendMetric.Delta -> Color(0xFFFFA726)
    TrendMetric.Temperature -> Color(0xFFEF6C5B)
    else -> MaterialTheme.colorScheme.primary
}

//MARK:格式化数值
//按照趋势指标要求的小数位格式化图表数值。
private fun TrendMetric.format(value: Double): String = when (this) {
    TrendMetric.Soc, TrendMetric.Delta, TrendMetric.MinimumCell -> "${compact(value, 1)} $unit"
    else -> "${compact(value, 2)} $unit"
}

//MARK:压缩数值
//缩短图表坐标轴数字并删除末尾零，减少标签相互遮挡。
private fun compact(value: Double, decimals: Int): String {
    val formatted = "%.${decimals}f".format(Locale.US, value)
    return formatted.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
}

private val trendHourFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val trendDayFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val fingerprintDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

//MARK:格式化指纹日
//formatFingerprintDate 将特征样本转换为适合当前页面展示的文本，并统一精度、单位或正负号格式。
private fun formatFingerprintDate(timestamp: Long): String = fingerprintDateFormatter.format(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
)

//MARK:格式化趋势时
//formatTrendTime 将趋势时间转换为适合当前页面展示的文本，并统一精度、单位或正负号格式。
private fun formatTrendTime(timestamp: Long, range: BatteryTrendRange): String {
    val time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return if (range == BatteryTrendRange.SevenDays || range == BatteryTrendRange.ThirtyDays) {
        trendDayFormatter.format(time)
    } else {
        trendHourFormatter.format(time)
    }
}
