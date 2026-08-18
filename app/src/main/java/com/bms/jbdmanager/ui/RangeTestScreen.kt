package com.bms.jbdmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.BrakeTestPhase


@Composable
internal fun RangeTestPage(
    state: BmsUiState,
    onRequestLocationPermission: () -> Unit,
    onClearSpeedRangeStats: () -> Unit
) {
    var selectedTarget by rememberSaveable { mutableIntStateOf(40) }
    var showClearConfirmation by remember { androidx.compose.runtime.mutableStateOf(false) }
    val selectedStats = state.trip.speedRangeStats.firstOrNull { it.targetSpeedKmh == selectedTarget }
        ?: state.trip.speedRangeStats.first()
    val currentStats = state.trip.speedRangeStats.firstOrNull { it.accepts(state.trip.currentSpeedKmh) }
    val sampleStatus = when {
        !state.trip.isTracking -> "连接 BMS 后自动开始分档统计"
        currentStats != null -> "当前 ${compactNumber(state.trip.currentSpeedKmh)}km/h，自动计入 ${currentStats.targetSpeedKmh}km/h 档"
        else -> "当前速度不在预设档位，综合行程继续记录"
    }
    val sampleColor = if (state.trip.isTracking && currentStats != null) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val estimatedRange = selectedStats.estimatedRemainingKm(state.trip.currentRemainingAh)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)),
                shape = RoundedCornerShape(15.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动速度续航", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            if (state.trip.isTracking) "自动统计中" else "等待连接",
                            color = if (state.trip.isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "行驶数据会自动归入最接近的速度档位，并长期累计保存。骑行样本越多，续航估算越稳定。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("点击查看不同速度的续航结果", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(25, 30, 35, 40, 45, 50, 55, 60).forEach { speed ->
                            val selected = selectedTarget == speed
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedTarget = speed },
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Text(
                                    "$speed",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 9.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = sampleColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).background(sampleColor, CircleShape))
                            Spacer(Modifier.width(7.dp))
                            Text(sampleStatus, color = sampleColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {
            MetricRow(
                Metric("${selectedStats.targetSpeedKmh}档有效里程", "${compactNumber(selectedStats.effectiveDistanceKm)} km", "仅该速度区间"),
                Metric(
                    "该速度预计续航",
                    estimatedRange?.let { "${compactNumber(it)} km" } ?: "采集中",
                    selectedStats.confidence,
                    if (estimatedRange != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            )
        }
        item {
            MetricRow(
                Metric(
                    "当前速度",
                    "${compactNumber(state.trip.currentSpeedKmh)} km/h",
                    currentStats?.let { "正在计入 ${it.targetSpeedKmh} 档" } ?: "未进入速度档",
                    if (currentStats != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                Metric(
                    "查看速度区间",
                    "${compactNumber(selectedStats.minimumSpeedKmh)}–${compactNumber(selectedStats.maximumSpeedKmh)}",
                    "${selectedStats.targetSpeedKmh} km/h 档"
                )
            )
        }
        item {
            MetricRow(
                Metric("该档消耗容量", "${compactNumber(selectedStats.consumedAh)} Ah", "该速度内积分"),
                Metric("该档消耗电量", "${compactNumber(selectedStats.consumedWh)} Wh", "电压 × 电流 × 时间")
            )
        }
        item {
            MetricRow(
                Metric(
                    "平均电耗",
                    selectedStats.ahPer100Km?.let { "${compactNumber(it)} Ah/100km" } ?: "采集中",
                    "容量电耗"
                ),
                Metric(
                    "能耗强度",
                    selectedStats.whPerKm?.let { "${compactNumber(it)} Wh/km" } ?: "采集中",
                    "长途比较值"
                )
            )
        }
        item {
            MetricRow(
                Metric(
                    "该档平均速度",
                    selectedStats.averageSpeedKmh?.let { "${compactNumber(it)} km/h" } ?: "采集中",
                    "有效样本平均值"
                ),
                Metric(
                    "当前剩余容量",
                    state.trip.currentRemainingAh?.let { "${compactNumber(it)} Ah" } ?: "--",
                    "用于计算剩余续航"
                )
            )
        }
        item {
            if (!state.locationPermissionGranted) {
                OutlinedButton(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("允许精确位置权限")
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { showClearConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空长期累计样本", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Text(
                if (state.trip.isTracking) {
                    "所有速度档位会在后台同步长期积累。切换按钮只查看对应档位结果；单档有效里程达到 3km 后提供初步估算。"
                } else {
                    "重新连接 BMS 后会在现有累计样本上继续统计，不会自动清零。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
            )
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("清空续航样本？") },
            text = { Text("所有速度档位长期累计的里程、耗电量和耗时都会清零，且无法恢复。") },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearSpeedRangeStats()
                    }
                ) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
