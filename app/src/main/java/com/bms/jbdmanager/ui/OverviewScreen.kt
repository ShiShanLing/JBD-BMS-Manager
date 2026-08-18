package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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


@Composable
internal fun Overview(
    state: BmsUiState,
    onRequestLocationPermission: () -> Unit
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
        item { SocHero(info) }
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
                    "单体压差/平均值",
                    deltaColor
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
        item { TripCard(state.trip, state.locationPermissionGranted, onRequestLocationPermission) }
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
private fun TripCard(
    trip: TripState,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripPrimaryMetric(
                    label = "已行驶",
                    value = if (trip.startedAtMillis == null) "--" else "${compactNumber(trip.distanceKm)} km",
                    modifier = Modifier.weight(1f)
                )
                TripPrimaryMetric(
                    label = "预计剩余续航",
                    value = trip.estimatedRemainingKm?.let { "${compactNumber(it)} km" } ?: "采集中",
                    modifier = Modifier.weight(1f)
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
private fun TripPrimaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp, maxLines = 1)
        }
    }
}

@Composable
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
