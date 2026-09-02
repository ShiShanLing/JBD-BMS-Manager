package com.bms.jbdmanager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PipScreen(state: BmsUiState) {
    val chargingModeResolver = remember { PipChargingModeResolver() }
    val info = state.basicInfo
    val charging = remember(info?.updatedAtMillis, info?.currentA, state.trip.currentSpeedKmh) {
        chargingModeResolver.update(
            currentA = info?.currentA,
            speedKmh = state.trip.currentSpeedKmh,
            nowMillis = SystemClock.elapsedRealtime()
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 6.dp)
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
    val regen = info != null && info.currentA > 0.05
    val accent = when {
        discharging -> MaterialTheme.colorScheme.primary
        regen -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = when {
        info == null -> "等待数据"
        info.stateOfChargePercent >= 100 -> "已充满"
        discharging -> "放电中"
        regen -> "动能回收"
        state.trip.currentSpeedKmh >= 0.5 -> "骑行中"
        else -> "静置"
    }
    val moving = state.trip.currentSpeedKmh >= 0.5
    val speedText = "${compactNumber(state.trip.currentSpeedKmh, 1)}"
    val rangeText = state.trip.estimatedRemainingKm?.let { "${compactNumber(it)} km" } ?: "采集中"
    val dischargeCurrent = if (discharging) abs(info!!.currentA) else 0.0
    val socProgress = (info?.stateOfChargePercent ?: 0) / 100f
    val todayKm = state.mileageHistory.todayDistanceKm()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PipSocBlock(soc = info?.stateOfChargePercent, accent = accent, compact = true)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("当前车速", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        speedText,
                        color = accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        lineHeight = 24.sp,
                        maxLines = 1
                    )
                    Text(
                        " km/h",
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 1.dp, bottom = 3.dp)
                    )
                }
            }
            PipStatusChip(
                status = status,
                accent = accent,
                compact = true,
                modifier = Modifier.width(52.dp)
            )
        }
        PipRidingSocBar(
            progress = socProgress,
            moving = moving,
            discharging = discharging,
            accent = accent,
            compact = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PipMetric(
                "今日总里程",
                "${compactNumber(todayKm, 1)} km",
                Modifier.weight(1f),
                accent,
                compact = true,
                labelFontSize = 10.sp,
                valueFontSize = 13.sp
            )
            PipMetric("剩余续航", rangeText, Modifier.weight(1f), accent, compact = true)
            PipMetric(
                "放电电流",
                if (discharging) "${compactNumber(dischargeCurrent, 1)} A" else "--",
                Modifier.weight(1f),
                if (discharging) accent else null,
                compact = true
            )
            PipMetric(
                "本次",
                if (state.trip.startedAtMillis != null) "${compactNumber(state.trip.distanceKm, 1)} km" else "--",
                Modifier.weight(1f),
                compact = true
            )
        }
    }
}

@Composable
private fun PipStatusChip(
    status: String,
    accent: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            status,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 6.dp else 8.dp,
                    vertical = if (compact) 2.dp else 3.dp
                ),
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 8.sp else 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal class PipChargingModeResolver {
    private var lastMovingAtMillis: Long? = null
    private var chargingCandidateSinceMillis: Long? = null
    private var chargingExitSinceMillis: Long? = null
    private var charging = false

    fun update(currentA: Double?, speedKmh: Double, nowMillis: Long): Boolean {
        if (speedKmh >= MOVING_SPEED_KMH) {
            lastMovingAtMillis = nowMillis
            chargingCandidateSinceMillis = null
            chargingExitSinceMillis = null
            charging = false
            return false
        }

        val current = currentA ?: run {
            resetCandidates()
            charging = false
            return false
        }
        if (charging) {
            if (current <= CHARGING_EXIT_CURRENT_A) {
                val exitSince = chargingExitSinceMillis ?: nowMillis.also {
                    chargingExitSinceMillis = it
                }
                if (nowMillis - exitSince >= CHARGING_EXIT_CONFIRM_MS) {
                    charging = false
                    resetCandidates()
                }
            } else {
                chargingExitSinceMillis = null
            }
            return charging
        }

        if (current <= CHARGING_ENTRY_CURRENT_A) {
            chargingCandidateSinceMillis = null
            return false
        }
        val candidateSince = chargingCandidateSinceMillis ?: nowMillis.also {
            chargingCandidateSinceMillis = it
        }
        val recentlyMoving = lastMovingAtMillis?.let {
            nowMillis - it < RECENT_MOVEMENT_GUARD_MS
        } == true
        if (!recentlyMoving && nowMillis - candidateSince >= CHARGING_ENTRY_CONFIRM_MS) {
            charging = true
            chargingExitSinceMillis = null
        }
        return charging
    }

    private fun resetCandidates() {
        chargingCandidateSinceMillis = null
        chargingExitSinceMillis = null
    }

    private companion object {
        const val MOVING_SPEED_KMH = 1.0
        const val CHARGING_ENTRY_CURRENT_A = 7.0
        const val CHARGING_EXIT_CURRENT_A = 1.0
        const val CHARGING_ENTRY_CONFIRM_MS = 10_000L
        const val RECENT_MOVEMENT_GUARD_MS = 15_000L
        const val CHARGING_EXIT_CONFIRM_MS = 3_000L
    }
}

@Composable
private fun PipRidingSocBar(
    progress: Float,
    moving: Boolean,
    discharging: Boolean,
    accent: Color,
    compact: Boolean = false
) {
    val fill = progress.coerceIn(0f, 1f)
    val animate = moving || discharging
    val infinite = rememberInfiniteTransition(label = "ridingShimmer")
    val shimmer by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ridingShimmerValue"
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 6.dp else 8.dp)
            .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(accent)
        )
        if (animate && fill > 0.04f) {
            val barWidth = maxWidth * fill
            val highlightWidth = barWidth * 0.28f
            val travel = barWidth - highlightWidth
            Box(
                Modifier
                    .offset(x = highlightWidth * -0.2f + travel * shimmer)
                    .fillMaxHeight()
                    .width(highlightWidth)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
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
private fun PipSocBlock(soc: Int?, accent: Color, compact: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            soc?.let { "$it%" } ?: "--",
            fontWeight = FontWeight.Black,
            fontSize = if (compact) 22.sp else 28.sp,
            lineHeight = if (compact) 24.sp else 30.sp,
            color = accent
        )
        Text(
            "SOC",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (compact) 8.sp else 10.sp
        )
    }
}

@Composable
private fun PipMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    compact: Boolean = false,
    labelFontSize: TextUnit? = null,
    valueFontSize: TextUnit? = null
) {
    val resolvedLabelSize = labelFontSize ?: if (compact) 8.sp else 10.sp
    val resolvedValueSize = valueFontSize ?: if (compact) 11.sp else 14.sp
    val resolvedValueLineHeight = when {
        valueFontSize != null -> 15.sp
        compact -> 13.sp
        else -> 16.sp
    }
    Column(modifier) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = resolvedLabelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = resolvedValueSize,
            lineHeight = resolvedValueLineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PipChargeBar(progress: Float, accent: Color) {
    val fill = progress.coerceIn(0f, 1f)
    Box(
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
                    trip = demo.trip.copy(currentA = -18.4, currentSpeedKmh = 41.2, currentSocPercent = 64, distanceMeters = 12_400.0)
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
