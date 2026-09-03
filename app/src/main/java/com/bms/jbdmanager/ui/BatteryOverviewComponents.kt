package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.TripState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt


internal data class Metric(
    val label: String,
    val value: String,
    val note: String,
    val valueColor: Color? = null,
    val onClick: (() -> Unit)? = null
)

@Composable
internal fun MetricRow(left: Metric, right: Metric) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricCard(left, Modifier.weight(1f).fillMaxHeight())
        MetricCard(right, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(
        modifier = if (metric.onClick != null) modifier.clickable(onClick = metric.onClick) else modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metric.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (metric.onClick != null) {
                    Text(
                        "›",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                metric.value,
                color = metric.valueColor ?: MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 18.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                metric.note,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SocHero(
    info: BmsBasicInfo,
    speedKmh: Double,
    onClick: (() -> Unit)? = null
) {
    val charging = info.isCharging(speedKmh)
    val batteryState = when {
        charging -> "正在充电"
        info.currentA < -0.05 -> "正在放电"
        info.currentA > 0.05 -> "动能回收"
        else -> "静置"
    }
    val currentText = if (kotlin.math.abs(info.currentA) <= 0.05) {
        "0.00A"
    } else {
        format(info.currentA, "A").replace(" ", "")
    }
    val powerText = if (abs(info.currentA) <= 0.05) {
        "0W"
    } else {
        "${abs(info.totalVoltageV * info.currentA).roundToInt()}W"
    }
    val batteryStateColor = when {
        charging -> MaterialTheme.colorScheme.primary
        info.currentA < -0.05 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { info.stateOfChargePercent / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${info.stateOfChargePercent}%", fontWeight = FontWeight.Black, fontSize = 21.sp)
                    Text("SOC", fontSize = 12.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "电池状态",
                    modifier = Modifier.height(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                )
                if (onClick != null) {
                    Text(
                        "点击切换演示场景",
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        lineHeight = 11.sp
                    )
                }
                Column {
                    Text(
                        batteryState,
                        color = batteryStateColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Text(
                                currentText,
                                color = batteryStateColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 18.sp,
                                maxLines = 1
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Text(
                                powerText,
                                color = batteryStateColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 18.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                info.productionDate?.let { Text("生产日期 $it", fontSize = 11.sp) }
            }
            RuntimeStatusColumn(info, Modifier.fillMaxHeight())
        }
    }
}

@Composable
private fun RuntimeStatusColumn(info: BmsBasicInfo, modifier: Modifier = Modifier) {
    val protection = protectionText(info.protectionMask)
    val balancing = info.balancingMask != 0L
    Column(
        modifier = modifier.width(82.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "运行状态",
            modifier = Modifier.height(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 14.sp
        )
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f)) {
            CompactStatus("充电 MOS", info.chargeMosEnabled, modifier = Modifier.weight(1f))
            CompactStatus("放电 MOS", info.dischargeMosEnabled, modifier = Modifier.weight(1f))
            CompactStatus("电池均衡", balancing, modifier = Modifier.weight(1f))
            CompactStatus(
                "电池保护",
                protection.isNotEmpty(),
                dangerWhenActive = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CompactStatus(
    label: String,
    active: Boolean,
    dangerWhenActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeColor = if (dangerWhenActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .then(
                    if (active) {
                        Modifier.background(activeColor, CircleShape)
                    } else {
                        Modifier.border(1.dp, inactiveColor, CircleShape)
                    }
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
    }
}

@Composable
internal fun TemperatureCard(info: BmsBasicInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(13.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("温度探头", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
            if (info.temperaturesC.isEmpty()) {
                Text(
                    "未返回温度数据",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
            } else {
                info.temperaturesC.forEachIndexed { index, temperature ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("T${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                            Text(format(temperature, "℃", 1), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CellsOverviewSection(cells: CellSummary?, cellCount: Int, balancingMask: Long, nearFull: Boolean) {
    if (cells == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp)
        ) {
            Text(
                "正在等待单体电压…",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text("单体电压 · ${cellCount}串", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallSummary("最低", cells.minimumMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
            SmallSummary("最高", cells.maximumMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
            SmallSummary(
                "压差",
                cells.deltaMv?.let { "$it mV" } ?: "--",
                Modifier.weight(1f),
                deltaAlertColor(cells.deltaMv, nearFull)
            )
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.millivolts.withIndex().chunked(5).forEach { rowCells ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowCells.forEach { (index, mv) ->
                        CellCard(
                            index = index,
                            mv = mv,
                            balancing = balancingMask and (1L shl index) != 0L,
                            min = cells.minimumMv,
                            max = cells.maximumMv,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(5 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SmallSummary(label: String, value: String, modifier: Modifier, valueColor: Color? = null) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

internal val AlertYellow = Color(0xFFFFC247)
internal val AlertOrange = Color(0xFFFF8A3D)

@Composable
internal fun deltaAlertColor(deltaMv: Int?, nearFull: Boolean): Color? {
    if (deltaMv == null) return null
    return if (nearFull) {
        when {
            deltaMv < 150 -> null
            deltaMv < 200 -> AlertYellow
            deltaMv < 300 -> AlertOrange
            else -> MaterialTheme.colorScheme.error
        }
    } else {
        when {
            deltaMv < 30 -> null
            deltaMv < 80 -> AlertYellow
            deltaMv < 150 -> AlertOrange
            else -> MaterialTheme.colorScheme.error
        }
    }
}

internal fun isNearFull(info: BmsBasicInfo, cells: CellSummary?): Boolean =
    info.stateOfChargePercent >= 95 || (cells?.maximumMv ?: 0) >= 3400

@Composable
internal fun temperatureAlertColor(
    temperatureC: Double,
    highLimitC: Double?,
    protectionTriggered: Boolean = false
): Color? {
    if (protectionTriggered) return MaterialTheme.colorScheme.error
    val limit = highLimitC ?: 50.0
    return when {
        temperatureC >= limit -> MaterialTheme.colorScheme.error
        temperatureC >= limit - 5.0 -> AlertOrange
        temperatureC >= limit - 10.0 -> AlertYellow
        else -> null
    }
}

@Composable
internal fun healthAlertColor(sohPercent: Double?): Color? = when {
    sohPercent == null || sohPercent >= 90.0 -> null
    sohPercent < 75.0 -> MaterialTheme.colorScheme.error
    sohPercent < 80.0 -> AlertOrange
    else -> AlertYellow
}

@Composable
private fun CellCard(index: Int, mv: Int, balancing: Boolean, min: Int?, max: Int?, modifier: Modifier = Modifier) {
    val accent = when (mv) {
        min -> MaterialTheme.colorScheme.secondary
        max -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(10.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 6.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("单体 ${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, lineHeight = 10.sp)
                Text(
                    "%.3f V".format(Locale.US, mv / 1000.0),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    maxLines = 1
                )
            }
            if (balancing) {
                Text(
                    "均衡",
                    modifier = Modifier.align(Alignment.TopStart),
                    color = Color(0xFFD0A8FF),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
internal fun EmptyReading(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun protectionText(mask: Int): List<String> {
    val names = listOf(
        "单体过压", "单体欠压", "总压过高", "总压过低",
        "充电高温", "充电低温", "放电高温", "放电低温",
        "充电过流", "放电过流", "短路", "前端芯片异常",
        "软件关闭 MOS", "充电 MOS 异常", "放电 MOS 异常"
    )
    return names.mapIndexedNotNull { index, name -> name.takeIf { mask and (1 shl index) != 0 } }
}

internal fun format(value: Double, unit: String, decimals: Int = 2): String =
    "%.${decimals}f %s".format(Locale.US, value, unit)

internal fun compactNumber(value: Double, decimals: Int = 2): String =
    "%.${decimals}f".format(Locale.US, value).trimEnd('0').trimEnd('.')
