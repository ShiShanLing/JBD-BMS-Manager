package com.bms.jbdmanager.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.R
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun MileageOnlyTripScreen(
    state: BmsUiState,
    onFinish: () -> Unit,
    onResetSegment: () -> Unit,
    onSetCountdownTarget: (Int) -> Unit,
    onAcknowledgeCountdown: () -> Unit,
    onEnterPictureInPicture: () -> Unit
) {
    var showFinishConfirmation by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.trip.startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    BackHandler { showFinishConfirmation = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GPS 行程", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(
                    "不连接电池，仅记录行驶里程并计入行程日历",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            IconButton(
                onClick = onEnterPictureInPicture,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_picture_in_picture),
                    contentDescription = "进入小窗",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(27.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        Text(
            "当前时速",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp
        )
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                formatSpeed(state.gpsSpeed.currentKmh),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold
            )
            Text(" km/h", modifier = Modifier.padding(bottom = 10.dp), fontSize = 15.sp)
        }
        Spacer(Modifier.height(18.dp))

        CountdownCard(
            distanceKm = state.trip.distanceKm,
            targetKm = state.trip.mileageCountdownTargetKm,
            remainingKm = state.trip.mileageCountdownRemainingKm,
            remainingPercent = state.trip.mileageCountdownRemainingPercent,
            reached = state.trip.mileageCountdownReached,
            onSetTarget = onSetCountdownTarget
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { showResetConfirmation = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("更换电池，里程清零")
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TripValueCard(
                title = "近 5 秒平均",
                value = "${formatSpeed(state.gpsSpeed.average5SecondsKmh)} km/h",
                modifier = Modifier.weight(1f)
            )
            TripValueCard(
                title = "最高时速",
                value = "${formatSpeed(state.gpsSpeed.maximumKmh)} km/h",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TripValueCard(
                title = "本次里程",
                value = String.format(Locale.US, "%.2f km", state.trip.distanceKm),
                modifier = Modifier.weight(1f),
                emphasize = true
            )
            TripValueCard(
                title = "今日累计",
                value = String.format(Locale.US, "%.2f km", state.mileageHistory.todayDistanceKm()),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TripValueCard(
                title = "行程时长",
                value = formatDuration(
                    (nowMillis - (state.trip.startedAtMillis ?: nowMillis)).coerceAtLeast(0L)
                ),
                modifier = Modifier.weight(1f)
            )
            TripValueCard(
                title = "定位状态",
                value = state.trip.gpsMessage.ifBlank { "正在等待 GPS" },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            "该模式不会参与耗电、续航、容量或电池健康度计算。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Button(
            onClick = { showFinishConfirmation = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            contentPadding = PaddingValues(horizontal = 18.dp)
        ) {
            Text("结束并保存行程", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (showFinishConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            title = { Text("结束本次 GPS 行程？") },
            text = { Text("本次里程会保存到行程历史和日历中。") },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) { Text("继续记录") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishConfirmation = false
                        onFinish()
                    }
                ) { Text("结束并保存", color = MaterialTheme.colorScheme.primary) }
            }
        )
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("开始下一块电池计程？") },
            text = { Text("当前 ${formatDistance(state.trip.distanceKm)} km 会先保存到行程历史，然后换电里程从 0 重新开始，总里程不会清空。") },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        onResetSegment()
                    }
                ) { Text("保存并重新计程", color = MaterialTheme.colorScheme.primary) }
            }
        )
    }
    if (state.trip.mileageCountdownReached && !state.trip.mileageCountdownAcknowledged) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("换电里程已到", color = MaterialTheme.colorScheme.error) },
            text = {
                Text(
                    "本块电池已经行驶 ${formatDistance(state.trip.distanceKm)} km，" +
                        "达到设定的 ${state.trip.mileageCountdownTargetKm} km，请及时寻找换电站。"
                )
            },
            confirmButton = {
                TextButton(onClick = onAcknowledgeCountdown) {
                    Text("我知道了", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

@Composable
private fun CountdownCard(
    distanceKm: Double,
    targetKm: Int,
    remainingKm: Double,
    remainingPercent: Int,
    reached: Boolean,
    onSetTarget: (Int) -> Unit
) {
    val accent = when {
        reached -> MaterialTheme.colorScheme.error
        remainingKm <= 5.0 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("换电倒计里程", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        if (reached) {
                            "已达到提醒里程 · 0%"
                        } else {
                            "剩余 ${formatDistance(remainingKm)} km · $remainingPercent%"
                        },
                        color = accent,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "已跑 ${formatDistance(distanceKm)} / $targetKm km",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(25, 30, 35, 40).forEach { preset ->
                    FilterChip(
                        selected = targetKm == preset,
                        onClick = { onSetTarget(preset) },
                        label = { Text("$preset km", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "精细调整",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { onSetTarget(targetKm - 1) },
                    enabled = targetKm > 5,
                    modifier = Modifier.size(38.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("−", fontSize = 20.sp)
                }
                Text(
                    "$targetKm km",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = { onSetTarget(targetKm + 1) },
                    enabled = targetKm < 200,
                    modifier = Modifier.size(38.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+", fontSize = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun TripValueCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false
) {
    Card(
        modifier = modifier.height(94.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(
                    value,
                    color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontSize = if (emphasize) 20.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
            }
        }
    }
}

private fun formatSpeed(value: Double): String = String.format(Locale.US, "%.1f", value.coerceAtLeast(0.0))

private fun formatDistance(value: Double): String = String.format(Locale.US, "%.1f", value.coerceAtLeast(0.0))

private fun formatDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}小时${minutes}分" else "${minutes}分钟"
}
