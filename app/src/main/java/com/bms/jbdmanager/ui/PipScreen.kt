package com.bms.jbdmanager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PipScreen(state: BmsUiState) {
    val charging = state.isCharging
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        AnimatedContent(
            targetState = charging,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "pipMode"
        ) { isCharging ->
            if (isCharging) PipChargingLayout(state) else PipRidingLayout(state)
        }
    }
}

@Composable
private fun PipRidingLayout(state: BmsUiState) {
    val info = state.basicInfo
    val discharging = info != null && info.currentA < -0.05
    val accent = when {
        discharging -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val status = when {
        info == null -> "等待数据"
        info.stateOfChargePercent >= 100 -> "已充满"
        discharging -> "放电中"
        info.currentA > 0.05 -> "动能回收"
        else -> "静置"
    }
    val dischargeCurrent = if (discharging) abs(info.currentA) else 0.0
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        PipSocBlock(soc = info?.stateOfChargePercent, accent = accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PipMetric("状态", status, Modifier.weight(1f), accent)
                PipMetric("放电电流", "${compactNumber(dischargeCurrent, 2)} A", Modifier.weight(1f), accent)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PipMetric(
                    "当前车速",
                    "${compactNumber(state.trip.currentSpeedKmh, 1)} km/h",
                    Modifier.weight(1f)
                )
                PipMetric(
                    "剩余续航",
                    state.trip.estimatedRemainingKm?.let { "${compactNumber(it)} km" } ?: "采集中",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PipChargingLayout(state: BmsUiState) {
    val info = state.basicInfo ?: return
    val cells = state.cells
    val fullAh = info.fullChargeCapacityAh ?: info.nominalCapacityAh
    val toFullAh = (fullAh - info.remainingCapacityAh).coerceAtLeast(0.0)
    val full = info.stateOfChargePercent >= 100 || toFullAh < 0.05
    val etaMinutes = if (!full && info.currentA > 0.05) {
        (toFullAh / info.currentA * 60.0).roundToInt().coerceAtLeast(0)
    } else {
        null
    }
    val accent = if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val deltaMv = cells?.deltaMv
    val deltaColor = deltaAlertColor(deltaMv, isNearFull(info, cells)) ?: accent
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PipSocBlock(soc = info.stateOfChargePercent, accent = accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("预计充满", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
                Text(
                    when {
                        full -> "已充满"
                        etaMinutes == null -> "--"
                        else -> formatEta(etaMinutes)
                    },
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    lineHeight = 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        PipChargeBar(progress = info.stateOfChargePercent / 100f, accent = accent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PipMetric("充电电流", "${compactNumber(info.currentA, 2)} A", Modifier.weight(1f), accent)
            PipMetric(
                "压差",
                deltaMv?.let { "$it mV" } ?: "--",
                Modifier.weight(1f),
                deltaColor
            )
        }
    }
}

@Composable
private fun PipSocBlock(soc: Int?, accent: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            soc?.let { "$it%" } ?: "--",
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            lineHeight = 30.sp,
            color = accent
        )
        Text("SOC", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun PipMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Column(modifier) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1
        )
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PipChargeBar(progress: Float, accent: Color) {
    val fill = progress.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(accent)
        )
    }
}

private fun formatEta(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
        hours > 0 -> "${hours}小时"
        else -> "${minutes}分"
    }
}

@Preview(name = "小窗-骑行", widthDp = 240, heightDp = 135, showBackground = true)
@Composable
private fun PipRidingPreview() {
    JbdBmsTheme {
        Surface {
            val demo = demoBmsState()
            PipScreen(
                demo.copy(
                    basicInfo = demo.basicInfo?.copy(currentA = -18.4, stateOfChargePercent = 64),
                    trip = demo.trip.copy(currentA = -18.4, currentSpeedKmh = 41.2, currentSocPercent = 64)
                )
            )
        }
    }
}

@Preview(name = "小窗-充电", widthDp = 240, heightDp = 135, showBackground = true)
@Composable
private fun PipChargingPreview() {
    JbdBmsTheme {
        Surface {
            PipScreen(demoBmsState())
        }
    }
}
