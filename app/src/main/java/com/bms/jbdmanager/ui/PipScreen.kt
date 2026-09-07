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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val layout = pipLayoutSpec(maxWidth.value, maxHeight.value)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding)
        ) {
            if (state.trip.isMileageOnly) {
                PipMileageOnlyLayout(state, layout)
            } else {
                AnimatedContent(
                    targetState = charging,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "pipMode"
                ) { isCharging ->
                    if (isCharging) PipChargingLayout(state, layout) else PipRidingLayout(state, layout)
                }
            }
        }
    }
}

internal fun resolvePipContentScale(widthDp: Float, heightDp: Float): Float =
    minOf(widthDp / 240f, heightDp / 135f).coerceIn(0.72f, 1.55f)

private data class PipLayoutSpec(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val headerSpacing: Dp,
    val metricSpacing: Dp,
    val topMetricColumnSpacing: Dp,
    val topMetricRowSpacing: Dp,
    val speedMetricSpacing: Dp,
    val bottomSpeedHeight: Dp,
    val barHeight: Dp,
    val socSize: TextUnit,
    val socLineHeight: TextUnit,
    val headerLabelSize: TextUnit,
    val speedRowLabelSize: TextUnit,
    val speedSize: TextUnit,
    val speedLineHeight: TextUnit,
    val speedUnitSize: TextUnit,
    val maximumSpeedSize: TextUnit,
    val maximumSpeedLineHeight: TextUnit,
    val maximumSpeedUnitSize: TextUnit,
    val metricLabelSize: TextUnit,
    val metricValueSize: TextUnit,
    val topMetricValueSize: TextUnit
)

private fun pipLayoutSpec(widthDp: Float, heightDp: Float): PipLayoutSpec {
    val scale = resolvePipContentScale(widthDp, heightDp)
    return PipLayoutSpec(
        horizontalPadding = (8f * scale).dp,
        verticalPadding = (6f * scale).dp,
        headerSpacing = (8f * scale).dp,
        metricSpacing = (6f * scale).dp,
        topMetricColumnSpacing = (10f * scale).dp,
        topMetricRowSpacing = (2f * scale).dp,
        speedMetricSpacing = (6f * scale).dp,
        bottomSpeedHeight = (34f * scale).dp,
        barHeight = (6f * scale).dp,
        socSize = (24f * scale).sp,
        socLineHeight = (26f * scale).sp,
        headerLabelSize = (9f * scale).sp,
        speedRowLabelSize = (9f * scale).sp,
        speedSize = (24f * scale).sp,
        speedLineHeight = (26f * scale).sp,
        speedUnitSize = (10f * scale).sp,
        maximumSpeedSize = (17f * scale).sp,
        maximumSpeedLineHeight = (19f * scale).sp,
        maximumSpeedUnitSize = (8f * scale).sp,
        metricLabelSize = (9f * scale).sp,
        metricValueSize = (12f * scale).sp,
        topMetricValueSize = (11f * scale).sp
    )
}

@Composable
private fun PipRidingLayout(state: BmsUiState, layout: PipLayoutSpec) {
    val info = state.basicInfo
    val discharging = info != null && info.currentA < -0.05
    val regen = info != null && info.currentA > 0.05
    val accent = when {
        discharging -> MaterialTheme.colorScheme.primary
        regen -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val moving = state.trip.currentSpeedKmh >= 0.5
    val speedText = compactNumber(state.trip.currentSpeedKmh, 1)
    val maximumSpeedText = compactNumber(state.gpsSpeed.maximumKmh, 1)
    val rangeText = state.trip.estimatedRemainingKm?.let { "${compactNumber(it)} km" } ?: "采集中"
    val dischargeCurrent = if (discharging) abs(info!!.currentA) else 0.0
    val socProgress = (info?.stateOfChargePercent ?: 0) / 100f
    val todayKm = state.mileageHistory.todayDistanceKm()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PipSocBlock(soc = info?.stateOfChargePercent, accent = accent, layout = layout)
            Spacer(Modifier.width(layout.headerSpacing))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(layout.topMetricRowSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(layout.topMetricColumnSpacing)
                ) {
                    PipMetric(
                        "今日里程",
                        "${compactNumber(todayKm, 1)} km",
                        Modifier.weight(1f),
                        accent,
                        labelFontSize = layout.metricLabelSize,
                        valueFontSize = layout.topMetricValueSize,
                        horizontalAlignment = Alignment.End
                    )
                    PipMetric(
                        "剩余续航",
                        rangeText,
                        Modifier.weight(1f),
                        accent,
                        labelFontSize = layout.metricLabelSize,
                        valueFontSize = layout.topMetricValueSize,
                        horizontalAlignment = Alignment.End
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(layout.topMetricColumnSpacing)
                ) {
                    PipMetric(
                        "放电电流",
                        if (discharging) "${compactNumber(dischargeCurrent, 1)} A" else "--",
                        Modifier.weight(1f),
                        if (discharging) accent else null,
                        labelFontSize = layout.metricLabelSize,
                        valueFontSize = layout.topMetricValueSize,
                        horizontalAlignment = Alignment.End
                    )
                    PipMetric(
                        "本次里程",
                        if (state.trip.startedAtMillis != null) "${compactNumber(state.trip.distanceKm, 1)} km" else "--",
                        Modifier.weight(1f),
                        labelFontSize = layout.metricLabelSize,
                        valueFontSize = layout.topMetricValueSize,
                        horizontalAlignment = Alignment.End
                    )
                }
            }
        }
        PipRidingSocBar(
            progress = socProgress,
            moving = moving,
            discharging = discharging,
            accent = accent,
            height = layout.barHeight
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(layout.speedMetricSpacing)
        ) {
            PipInlineSpeedMetric(
                label = "当前",
                speed = speedText,
                accent = accent,
                valueSize = layout.maximumSpeedSize,
                lineHeight = layout.maximumSpeedLineHeight,
                unitSize = layout.maximumSpeedUnitSize,
                labelSize = layout.speedRowLabelSize,
                modifier = Modifier.weight(1f).height(layout.bottomSpeedHeight)
            )
            PipInlineSpeedMetric(
                label = "最高",
                speed = maximumSpeedText,
                accent = MaterialTheme.colorScheme.onSurface,
                valueSize = layout.maximumSpeedSize,
                lineHeight = layout.maximumSpeedLineHeight,
                unitSize = layout.maximumSpeedUnitSize,
                labelSize = layout.speedRowLabelSize,
                modifier = Modifier.weight(1f).height(layout.bottomSpeedHeight)
            )
        }
    }
}

@Composable
private fun PipMileageOnlyLayout(state: BmsUiState, layout: PipLayoutSpec) {
    val trip = state.trip
    val remainingKm = trip.mileageCountdownRemainingKm
    val remainingPercent = trip.mileageCountdownRemainingPercent
    val reached = trip.mileageCountdownReached
    val accent = when {
        reached -> MaterialTheme.colorScheme.error
        remainingKm <= 5.0 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val targetKm = trip.mileageCountdownTargetKm.coerceAtLeast(1)
    val progress = (remainingKm / targetKm).toFloat().coerceIn(0f, 1f)
    val todayKm = state.mileageHistory.todayDistanceKm()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.9f), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "剩余",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = layout.headerLabelSize,
                        modifier = Modifier.padding(end = 3.dp, bottom = 2.dp),
                        maxLines = 1
                    )
                    Text(
                        "$remainingPercent%",
                        color = accent,
                        fontWeight = FontWeight.Black,
                        fontSize = layout.socSize,
                        lineHeight = layout.socLineHeight,
                        maxLines = 1
                    )
                }
                Text(
                    if (reached) "已达到提醒里程" else "${compactNumber(remainingKm, 1)} km 可用",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = layout.headerLabelSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(layout.headerSpacing))
            Column(
                modifier = Modifier.weight(1.1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(layout.topMetricRowSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(layout.topMetricColumnSpacing)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            "本段里程",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = layout.metricLabelSize,
                            maxLines = 1,
                            textAlign = TextAlign.End
                        )
                        val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = accent,
                                        fontSize = layout.topMetricValueSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(compactNumber(trip.distanceKm, 1))
                                }
                                withStyle(
                                    SpanStyle(
                                        color = secondaryColor,
                                        fontSize = layout.metricLabelSize,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) {
                                    append("/${targetKm}km")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            lineHeight = layout.topMetricValueSize * 1.15f,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.End
                        )
                    }
                    PipMetric(
                        "今日累计",
                        "${compactNumber(todayKm, 1)} km",
                        Modifier.weight(1f),
                        labelFontSize = layout.metricLabelSize,
                        valueFontSize = layout.topMetricValueSize,
                        horizontalAlignment = Alignment.End
                    )
                }
                PipMetric(
                    "近5秒平均",
                    "${compactNumber(state.gpsSpeed.average5SecondsKmh, 1)} km/h",
                    Modifier.fillMaxWidth(),
                    labelFontSize = layout.metricLabelSize,
                    valueFontSize = layout.topMetricValueSize,
                    horizontalAlignment = Alignment.End
                )
            }
        }
        PipChargeBar(progress = progress, accent = accent, height = layout.barHeight)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(layout.speedMetricSpacing)
        ) {
            PipInlineSpeedMetric(
                label = "当前",
                speed = compactNumber(state.gpsSpeed.currentKmh, 1),
                accent = accent,
                valueSize = layout.maximumSpeedSize,
                lineHeight = layout.maximumSpeedLineHeight,
                unitSize = layout.maximumSpeedUnitSize,
                labelSize = layout.speedRowLabelSize,
                modifier = Modifier.weight(1f).height(layout.bottomSpeedHeight)
            )
            PipInlineSpeedMetric(
                label = "最高",
                speed = compactNumber(state.gpsSpeed.maximumKmh, 1),
                accent = MaterialTheme.colorScheme.onSurface,
                valueSize = layout.maximumSpeedSize,
                lineHeight = layout.maximumSpeedLineHeight,
                unitSize = layout.maximumSpeedUnitSize,
                labelSize = layout.speedRowLabelSize,
                modifier = Modifier.weight(1f).height(layout.bottomSpeedHeight)
            )
        }
    }
}

@Composable
private fun PipInlineSpeedMetric(
    label: String,
    speed: String,
    accent: Color,
    valueSize: TextUnit,
    lineHeight: TextUnit,
    unitSize: TextUnit,
    labelSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                speed,
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = valueSize,
                lineHeight = lineHeight,
                maxLines = 1
            )
            Text(
                " km/h",
                color = accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = unitSize,
                modifier = Modifier.padding(start = 2.dp),
                maxLines = 1
            )
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = labelSize,
                modifier = Modifier.padding(start = 5.dp),
                maxLines = 1
            )
        }
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
    height: Dp
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
            .height(height)
            .clip(RoundedCornerShape(height))
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
private fun PipChargingLayout(state: BmsUiState, layout: PipLayoutSpec) {
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
            PipSocBlock(soc = info.stateOfChargePercent, accent = accent, layout = layout)
            Spacer(Modifier.width(layout.headerSpacing))
            Column(Modifier.weight(1f)) {
                Text(
                    "预计充满",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = layout.headerLabelSize,
                    maxLines = 1
                )
                Text(
                    when {
                        full -> "已充满"
                        etaMinutes == null -> "--"
                        else -> formatEta(etaMinutes)
                    },
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = layout.speedSize,
                    lineHeight = layout.speedLineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        PipChargeBar(progress = info.stateOfChargePercent / 100f, accent = accent, height = layout.barHeight)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.metricSpacing)) {
            PipMetric(
                "充电电流",
                "${compactNumber(info.currentA, 2)} A",
                Modifier.weight(1f),
                accent,
                labelFontSize = layout.metricLabelSize,
                valueFontSize = layout.metricValueSize
            )
            PipMetric(
                "压差",
                deltaMv?.let { "$it mV" } ?: "--",
                Modifier.weight(1f),
                deltaColor,
                labelFontSize = layout.metricLabelSize,
                valueFontSize = layout.metricValueSize
            )
        }
    }
}

@Composable
private fun PipSocBlock(soc: Int?, accent: Color, layout: PipLayoutSpec) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            soc?.let { "$it%" } ?: "--",
            fontWeight = FontWeight.Black,
            fontSize = layout.socSize,
            lineHeight = layout.socLineHeight,
            color = accent
        )
        Text(
            "SOC",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = layout.headerLabelSize
        )
    }
}

@Composable
private fun PipMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    labelFontSize: TextUnit? = null,
    valueFontSize: TextUnit? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    val resolvedLabelSize = labelFontSize ?: 10.sp
    val resolvedValueSize = valueFontSize ?: 14.sp
    val resolvedValueLineHeight = when {
        valueFontSize != null -> valueFontSize * 1.15f
        else -> 16.sp
    }
    val textAlignment = if (horizontalAlignment == Alignment.End) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = resolvedLabelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlignment
        )
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = resolvedValueSize,
            lineHeight = resolvedValueLineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlignment
        )
    }
}

@Composable
private fun PipChargeBar(progress: Float, accent: Color, height: Dp) {
    val fill = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height))
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
