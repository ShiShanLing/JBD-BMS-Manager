package com.bms.jbdmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.bms.jbdmanager.R
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.MileagePeriod
import com.bms.jbdmanager.model.TripCategory
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
//MARK:自行车页面
//展示自行车独立 GPS 行程、有效骑行时间与估算热量，并提供体重调整和结束归档入口。
internal fun BicycleTripScreen(
    state: BmsUiState,
    onFinish: () -> Unit,
    onSetBodyWeight: (Double) -> Unit,
    onEnterPictureInPicture: () -> Unit
) {
    var showWeightDialog by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.trip.startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    // 用户主动返回即视为结束本段骑行；先归档再离开页面，不再增加一次确认操作。
    BackHandler { onFinish() }
    val bicycleHistory = state.mileageHistory.forCategory(TripCategory.Bicycle)
    // 每次重新开始骑行时本次热量会从零累计；页面主卡片需要合并今天已经归档的骑行和当前活动行程。
    val todayBicycleSummary = bicycleHistory.periodSummary(MileagePeriod.Day)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自行车骑行", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("独立记录里程、速度、时长和估算热量", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            IconButton(onClick = onEnterPictureInPicture, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_picture_in_picture),
                    contentDescription = "进入小窗",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(27.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(22.dp))
            Text("当前时速", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatSpeed(state.gpsSpeed.currentKmh), color = MaterialTheme.colorScheme.primary, fontSize = 54.sp, fontWeight = FontWeight.Bold)
                Text(" km/h", modifier = Modifier.padding(bottom = 10.dp), fontSize = 15.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TripValueCard("本次里程", String.format(Locale.US, "%.2f km", state.trip.distanceKm), Modifier.weight(1f), true)
                TripValueCard("今日总热量", String.format(Locale.US, "%.0f kcal", todayBicycleSummary.estimatedCaloriesKcal), Modifier.weight(1f), true)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TripValueCard("近 5 秒平均", "${formatSpeed(state.gpsSpeed.average5SecondsKmh)} km/h", Modifier.weight(1f))
                TripValueCard("最高时速", "${formatSpeed(state.gpsSpeed.maximumKmh)} km/h", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TripValueCard("骑行均速", String.format(Locale.US, "%.1f km/h", state.trip.bicycleAverageSpeedKmh), Modifier.weight(1f))
                TripValueCard("有效骑行", formatSeconds(state.trip.bicycleMovingDurationSeconds), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TripValueCard(
                    "总计时",
                    formatSeconds(((nowMillis - (state.trip.startedAtMillis ?: nowMillis)).coerceAtLeast(0L)) / 1_000.0),
                    Modifier.weight(1f)
                )
                BicycleWeightCard(
                    weightKg = state.trip.bicycleBodyWeightKg,
                    onClick = { showWeightDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TripValueCard("今日自行车", String.format(Locale.US, "%.2f km", bicycleHistory.todayDistanceKm()), Modifier.weight(1f))
                TripValueCard("定位状态", state.trip.gpsMessage.ifBlank { "等待 GPS" }, Modifier.weight(1f))
            }
            Text(
                "热量按速度分段 MET 与有效骑行时间估算；坡度、风阻和心率未知，因此只适合观察趋势。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)
            )
        }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("保存并退出", fontWeight = FontWeight.SemiBold)
        }
    }

    if (showWeightDialog) {
        var weight by remember(state.trip.bicycleBodyWeightKg) { mutableStateOf(state.trip.bicycleBodyWeightKg.toInt()) }
        AlertDialog(
            onDismissRequest = { showWeightDialog = false },
            title = { Text("设置体重") },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { weight = (weight - 1).coerceAtLeast(30) }) { Text("−") }
                    Text("$weight kg", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { weight = (weight + 1).coerceAtMost(250) }) { Text("+") }
                }
            },
            dismissButton = { TextButton(onClick = { showWeightDialog = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { onSetBodyWeight(weight.toDouble()); showWeightDialog = false }) { Text("保存") } }
        )
    }
}

@Composable
//MARK:体重卡片
//以与行程指标一致的小卡片展示当前体重；点击后打开体重调整弹框。
private fun BicycleWeightCard(weightKg: Double, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.height(94.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("体重 · 点击调整", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(
                "${compactNumber(weightKg, 1)} kg",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

//MARK:格式化时长
//把有效骑行秒数格式化为紧凑的小时分钟文本，停车时间不会进入该值。
private fun formatSeconds(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).toInt().coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}
