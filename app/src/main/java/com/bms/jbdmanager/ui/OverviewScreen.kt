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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.bms.jbdmanager.model.MileageHistoryState
import com.bms.jbdmanager.model.RegenerationPeak
import com.bms.jbdmanager.model.TripState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


@Composable
//MARK:电池概览页
//Overview 绘制概览组件，并根据参数决定文案、数值、颜色及交互状态。
internal fun Overview(
    state: BmsUiState,
    onRequestLocationPermission: () -> Unit,
    onCyclePreviewScenario: (() -> Unit)? = null,
    onOpenFullChargeStats: () -> Unit = {}
) {
    val info = state.basicInfo
    if (info == null) {
        EmptyReading("正在等待 BMS 基本数据…")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { SocHero(info, state.trip.currentSpeedKmh, onClick = onCyclePreviewScenario) }
        item { GpsSpeedPanel(state.gpsSpeed) }
        item {
            val cells = state.cells
            val nearFull = isNearFull(info, cells)
            val deltaColor = deltaAlertColor(cells?.deltaMv, nearFull)
            val voltageSummary = if (cells?.millivolts.isNullOrEmpty()) {
                "--"
            } else {
                val averageVoltage = cells!!.millivolts.average() / 1000.0
                "${cells.deltaMv ?: 0} mV/${"%.3f".format(Locale.US, averageVoltage)} V"
            }
            MetricRow(
                Metric(
                    "剩余容量/总容量",
                    "${compactNumber(info.remainingCapacityAh)}/${compactNumber(info.nominalCapacityAh)} Ah",
                    "剩余/总安时"
                ),
                Metric(
                    "压差/平均电压",
                    voltageSummary,
                    "点击查看满充统计",
                    deltaColor,
                    onClick = onOpenFullChargeStats
                )
            )
        }
        item {
            val sohColor = healthAlertColor(info.estimatedSohPercent)
            MetricRow(
                Metric("电池当前总电压", format(info.totalVoltageV, "V"), "实时总压"),
                Metric(
                    "循环次数/健康度",
                    "${info.cycleCount} 次 / ${info.estimatedSohPercent?.let { format(it, "%") } ?: "--"}",
                    "BMS 记录/估算 SOH",
                    sohColor
                )
            )
        }
        item { TripCard(state.trip, state.mileageHistory, info.remainingCapacityAh, state.locationPermissionGranted, onRequestLocationPermission) }
        item { TemperatureCard(info) }
        item {
            CellsOverviewSection(
                state.cells,
                info.cellCount,
                info.balancingMask,
                isNearFull(info, state.cells)
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
//MARK:本次行程卡
//TripCard 绘制行程卡片卡片，将同一主题的标题、关键数值和辅助信息组合展示。
private fun TripCard(
    trip: TripState,
    mileageHistory: MileageHistoryState,
    remainingAhHint: Double?,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit
) {
    val todayKm = mileageHistory.todayDistanceKm()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GPS 本次行程", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (trip.isTracking) "记录中 · ${trip.estimateConfidence}" else trip.estimateConfidence,
                    color = if (trip.isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "今日总里程",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${compactNumber(todayKm)} km",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    lineHeight = 22.sp
                )
            }
            Spacer(Modifier.height(7.dp))
            var showSpeedRangeDialog by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripPrimaryMetric(
                    label = "已行驶",
                    value = if (trip.startedAtMillis == null) "--" else "${compactNumber(trip.distanceKm)} km",
                    modifier = Modifier.weight(1f)
                )
                TripPrimaryMetric(
                    label = "本次预估续航",
                    value = trip.estimatedRemainingKm?.let { "${compactNumber(it)} km" } ?: "采集中",
                    modifier = Modifier.weight(1f),
                    onHelp = { showSpeedRangeDialog = true }
                )
            }
            if (showSpeedRangeDialog) {
                SpeedRangeEstimateDialog(
                    trip = trip,
                    remainingAh = trip.currentRemainingAh ?: remainingAhHint,
                    onDismiss = { showSpeedRangeDialog = false }
                )
            }
            Spacer(Modifier.height(7.dp))
            TripDetailRow(
                "SOC",
                trip.startSocPercent?.let { start ->
                    buildAnnotatedString {
                        append("$start%")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" → ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append("${trip.currentSocPercent ?: start}%")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append("（下降 ${trip.socDropPercent ?: 0}%）")
                        }
                    }
                } ?: AnnotatedString("等待 BMS 数据")
            )
            TripDetailRow(
                "剩余容量",
                trip.startRemainingAh?.let { start ->
                    buildAnnotatedString {
                        append(compactNumber(start))
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" → ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append("${compactNumber(trip.currentRemainingAh ?: start)} Ah")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append("（消耗 ${compactNumber(trip.consumedAh)} Ah）")
                        }
                    }
                } ?: AnnotatedString("等待 BMS 数据")
            )
            TripDetailRow(
                "平均电耗",
                buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = if (trip.ahPer100Km == null) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(trip.ahPer100Km?.let { "${compactNumber(it)} Ah/100km" } ?: "采集中")
                    }
                    trip.whPerKm?.let {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" · ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append("${compactNumber(it)} Wh/km")
                        }
                    }
                }
            )
            Spacer(Modifier.height(7.dp))
            RegenerationPeakPanel(
                current = trip.maximumRegeneration,
                historical = mileageHistory.maximumRegeneration
            )
            if (!locationPermissionGranted) {
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("允许精确位置并开始行程", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
//MARK:回收峰值卡
//并列展示本次行程和历史行程的最大动能回收事件，包括同一时刻的电流、功率、车速和时间。
private fun RegenerationPeakPanel(current: RegenerationPeak?, historical: RegenerationPeak?) {
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RegenerationPeakValue("本次最大回收", current, Modifier.weight(1f))
            RegenerationPeakValue("历史最大回收", historical, Modifier.weight(1f))
        }
    }
}

@Composable
//MARK:回收峰值项
//格式化一条回收峰值；没有可信的骑行回收样本时明确显示“等待记录”，避免用零值造成误解。
private fun RegenerationPeakValue(label: String, peak: RegenerationPeak?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            peak?.let { "${compactNumber(it.currentA, 1)} A · ${compactNumber(it.powerW, 0)} W" } ?: "等待记录",
            color = if (peak == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        if (peak != null) {
            Text(
                "${compactNumber(peak.speedKmh, 1)} km/h · ${formatRegenerationTime(peak.recordedAtMillis)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1
            )
        }
    }
}

//MARK:峰值时间
//把回收峰值毫秒时间转换为本地月日和时分，便于与历史骑行记录对应。
private fun formatRegenerationTime(timestamp: Long): String = regenerationTimeFormatter.format(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
)

private val regenerationTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
//MARK:GPS速度卡
//GpsSpeedPanel 绘制当前、近五秒平均和本次最高 GPS 速度三项指标。
private fun GpsSpeedPanel(speed: GpsSpeedState) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                "GPS 时速 · km/h",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                GpsSpeedMetric("当前时速", speed.currentKmh, Modifier.weight(1f))
                GpsSpeedMetric("近5秒平均", speed.average5SecondsKmh, Modifier.weight(1f))
                GpsSpeedMetric("最高时速", speed.maximumKmh, Modifier.weight(1f))
            }
        }
    }
}

@Composable
//MARK:速度指标
//GpsSpeedMetric 按统一宽度绘制单项 GPS 速度标题、数字和 km/h 单位。
private fun GpsSpeedMetric(label: String, value: Double, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        Text(
            compactNumber(value, 1),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 22.sp,
            maxLines = 1
        )
    }
}

@Composable
//MARK:行程主指标
//TripPrimaryMetric 绘制行程指标组件，并根据参数决定文案、数值、颜色及交互状态。
private fun TripPrimaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onHelp: (() -> Unit)? = null
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (onHelp != null) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
                            .clickable(onClick = onHelp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "?",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp, maxLines = 1)
        }
    }
}

@Composable
//MARK:分速续航弹框
//SpeedRangeEstimateDialog 展示速度续航弹框弹框，按当前状态控制按钮可用性，并通过回调提交或取消操作。
private fun SpeedRangeEstimateDialog(
    trip: TripState,
    remainingAh: Double?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("各速度预估续航") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "按历史速度档电耗，结合当前剩余容量估算。样本不足的档位显示为采集中。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "速度",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(72.dp)
                    )
                    Text(
                        "预估续航",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "样本",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                trip.speedRangeStats.forEach { stats ->
                    val active = stats.accepts(trip.currentSpeedKmh)
                    val remainingKm = stats.estimatedRemainingKm(remainingAh)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${stats.targetSpeedKmh} km/h",
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.width(72.dp)
                        )
                        Text(
                            remainingKm?.let { "${compactNumber(it)} km" } ?: "采集中",
                            color = if (remainingKm != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (remainingKm != null) {
                                "${stats.confidence} · ${compactNumber(stats.effectiveDistanceKm)} km"
                            } else {
                                "样本 ${compactNumber(stats.effectiveDistanceKm)} km"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
//MARK:行程详情行
//TripDetailRow 在一行内排列行程数据行的名称、数值和状态，统一对齐方式与间距。
private fun TripDetailRow(label: String, value: AnnotatedString) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(68.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 2
        )
    }
}
