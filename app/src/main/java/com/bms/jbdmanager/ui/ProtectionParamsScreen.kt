package com.bms.jbdmanager.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.JbdProtectionParams
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import kotlin.math.roundToInt

@Composable
//MARK:保护参数页
//ProtectionParamsPage 组织保护参数页面的完整页面结构，组合内容区和操作入口，并把事件交给状态持有层。
internal fun ProtectionParamsPage(
    state: BmsUiState,
    onRefresh: () -> Unit,
    readOnly: Boolean = false
) {
    val params = state.protectionParams
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { ChargeStatusSection(state) }
        if (params != null) {
            item {
                Text(
                    if (readOnly) "最后一次保存的只读阈值" else "只读阈值，不会修改设置",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 12.sp
                )
            }
            item {
                ParamGroup("单体电压") {
                    ParamPair("过充保护", voltageText(params.cellOvervoltageV), "过充恢复", voltageText(params.cellOvervoltageReleaseV))
                    ParamPair("欠压保护", voltageText(params.cellUndervoltageV), "欠压恢复", voltageText(params.cellUndervoltageReleaseV))
                    ParamPair("充满电压", voltageText(params.fullChargeVoltageV), null, null)
                }
            }
            item {
                ParamGroup("整包电压") {
                    ParamPair("过充保护", voltageText(params.packOvervoltageV), "过充恢复", voltageText(params.packOvervoltageReleaseV))
                    ParamPair("欠压保护", voltageText(params.packUndervoltageV), "欠压恢复", voltageText(params.packUndervoltageReleaseV))
                }
            }
            item {
                ParamGroup("过流") {
                    ParamPair("充电过流", currentText(params.chargeOvercurrentA), "放电过流", currentText(params.dischargeOvercurrentA))
                }
            }
            item {
                ParamGroup("充电温度") {
                    ParamPair("高温保护", tempText(params.chargeHighTempC), "高温恢复", tempText(params.chargeHighTempReleaseC))
                    ParamPair("低温保护", tempText(params.chargeLowTempC), "低温恢复", tempText(params.chargeLowTempReleaseC))
                }
            }
            item {
                ParamGroup("放电温度") {
                    ParamPair("高温保护", tempText(params.dischargeHighTempC), "高温恢复", tempText(params.dischargeHighTempReleaseC))
                    ParamPair("低温保护", tempText(params.dischargeLowTempC), "低温恢复", tempText(params.dischargeLowTempReleaseC))
                }
            }
        } else {
            item {
                Text(
                    if (state.protectionParamsLoading) "正在读取保护参数…"
                    else state.protectionParamsError ?: "尚未读取到保护参数",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        if (!state.protectionParamsError.isNullOrBlank() && params != null) {
            item {
                Text(state.protectionParamsError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
        if (!readOnly) {
            item {
                CompactRefreshButton(state.protectionParamsLoading, onRefresh)
            }
        }
    }
}

@Composable
//MARK:充电状态区
//ChargeStatusSection 绘制充电状态组件，并根据参数决定文案、数值、颜色及交互状态。
private fun ChargeStatusSection(state: BmsUiState) {
    val info = state.basicInfo
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (info == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
                Text("充电情况", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("等待 BMS 数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            return@Card
        }
        val charging = info.isCharging(state.trip.currentSpeedKmh)
        val fullAh = info.fullChargeCapacityAh ?: info.nominalCapacityAh
        val toFullAh = (fullAh - info.remainingCapacityAh).coerceAtLeast(0.0)
        val full = info.stateOfChargePercent >= 100 || toFullAh < 0.05
        val etaMinutes = if (charging && !full) {
            (toFullAh / info.currentA * 60.0).roundToInt().coerceAtLeast(0)
        } else null
        val accent = when {
            full -> MaterialTheme.colorScheme.primary
            charging -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("充电情况", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                ChargeStatusChip(charging = charging, full = full)
            }
            Spacer(Modifier.height(10.dp))
            Text("预计充满", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 13.sp)
            ChargeEtaHero(full = full, charging = charging, totalMinutes = etaMinutes, accent = accent)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChargeSocBar(
                    progress = info.stateOfChargePercent / 100f,
                    charging = charging && !full,
                    accent = accent,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${info.stateOfChargePercent}%",
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChargeHeroMetric("当前总压", format(info.totalVoltageV, "V", 2), Modifier.weight(1f))
                ChargeHeroMetric(
                    "充电电流",
                    if (charging) currentText(info.currentA) else "--",
                    Modifier.weight(1f),
                    valueColor = if (charging) accent else null
                )
                ChargeHeroMetric(
                    "剩余容量(Ah)",
                    "${compactNumber(info.remainingCapacityAh)}/${compactNumber(info.nominalCapacityAh)}",
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusToggleChip("充电 MOS", info.chargeMosEnabled)
                StatusToggleChip(
                    "均衡",
                    info.balancingMask != 0L || (info.balancingCurrentMa ?: 0) != 0
                )
            }
            if (info.temperaturesC.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ChargeTemperatureLine(
                    temperatures = info.temperaturesC,
                    highLimitC = state.protectionParams?.chargeHighTempC,
                    protectionTriggered = info.protectionMask and (1 shl 4) != 0
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            Spacer(Modifier.height(10.dp))
            CellLiveFooter(state.cells, info)
        }
    }
}

@Composable
//MARK:充电温度行
//ChargeTemperatureLine 在一行内排列充电温度的名称、数值和状态，统一对齐方式与间距。
private fun ChargeTemperatureLine(
    temperatures: List<Double>,
    highLimitC: Double?,
    protectionTriggered: Boolean
) {
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val hottest = temperatures.maxOrNull()
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = normal, fontSize = 10.sp)) { append("温度  ") }
            temperatures.forEachIndexed { index, temperature ->
                if (index > 0) withStyle(SpanStyle(color = normal, fontSize = 10.sp)) { append(" / ") }
                val triggered = protectionTriggered && temperature == hottest
                val warn = temperatureAlertColor(temperature, highLimitC, triggered)
                withStyle(
                    SpanStyle(
                        color = warn ?: normal,
                        fontWeight = if (warn != null) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp
                    )
                ) {
                    append(compactNumber(temperature, 1))
                }
            }
            withStyle(SpanStyle(color = normal, fontSize = 10.sp)) { append(" ℃") }
        },
        lineHeight = 14.sp
    )
}

@Composable
//MARK:状态开关项
//StatusToggleChip 展示状态的可选入口，突出当前项并通过回调上报切换结果。
private fun StatusToggleChip(label: String, enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = if (enabled) 0.22f else 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            "$label ${if (enabled) "开" else "关"}",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
//MARK:充电状态标签
//ChargeStatusChip 展示充电状态的可选入口，突出当前项并通过回调上报切换结果。
private fun ChargeStatusChip(charging: Boolean, full: Boolean) {
    val (label, color) = when {
        full -> "已充满" to MaterialTheme.colorScheme.primary
        charging -> "正在充电" to MaterialTheme.colorScheme.secondary
        else -> "未充电" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val infinite = rememberInfiniteTransition(label = "chargePulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chargePulseValue"
    )
    val live = charging && !full
    val pulseAmount = if (live) pulse else 1f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (live) {
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            scaleX = 0.7f + 0.5f * pulseAmount
                            scaleY = 0.7f + 0.5f * pulseAmount
                            alpha = 0.55f * (1f - pulseAmount)
                        }
                        .background(color, CircleShape)
                )
            }
            Box(Modifier.size(7.dp).background(color.copy(alpha = if (live) pulseAmount else 1f), CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
//MARK:充电电量条
//ChargeSocBar 根据充电样本计算坐标和比例，并绘制趋势、柱形或进度信息。
private fun ChargeSocBar(
    progress: Float,
    charging: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val fill = progress.coerceIn(0f, 1f)
    val infinite = rememberInfiniteTransition(label = "chargeShimmer")
    val shimmer by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "chargeShimmerValue"
    )
    val shimmerAmount = if (charging) shimmer else 0f
    BoxWithConstraints(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(accent)
        )
        if (charging && fill > 0.04f) {
            val barWidth = maxWidth * fill
            val highlightWidth = barWidth * 0.28f
            val travel = barWidth - highlightWidth
            Box(
                Modifier
                    .offset(x = highlightWidth * -0.2f + travel * shimmerAmount)
                    .fillMaxHeight()
                    .width(highlightWidth)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.42f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@Composable
//MARK:充电时长卡
//ChargeEtaHero 绘制充电组件，并根据参数决定文案、数值、颜色及交互状态。
private fun ChargeEtaHero(
    full: Boolean,
    charging: Boolean,
    totalMinutes: Int?,
    accent: Color
) {
    when {
        full -> Text("已充满", color = accent, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 36.sp)
        !charging || totalMinutes == null -> Text("--", color = accent, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 36.sp)
        else -> {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            Row(verticalAlignment = Alignment.Bottom) {
                if (hours > 0) {
                    Text("$hours", color = accent, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 36.sp)
                    Text("小时", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("$minutes", color = accent, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 36.sp)
                Text("分", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
            }
        }
    }
}

@Composable
//MARK:充电主指标
//ChargeHeroMetric 绘制充电指标组件，并根据参数决定文案、数值、颜色及交互状态。
private fun ChargeHeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            maxLines = 1
        )
    }
}

@Composable
//MARK:实时单体底栏
//CellLiveFooter 绘制单体组件，并根据参数决定文案、数值、颜色及交互状态。
private fun CellLiveFooter(
    cells: CellSummary?,
    info: BmsBasicInfo
) {
    val millivolts = cells?.millivolts.orEmpty()
    Text("单体现状", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (millivolts.isEmpty()) {
        Text("等待单体电压", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        return
    }
    val deltaMv = cells?.deltaMv
    val deltaColor = deltaAlertColor(deltaMv, isNearFull(info, cells))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChargeHeroMetric("最高电压", cellVoltageText(cells?.maximumMv), Modifier.weight(1f))
        ChargeHeroMetric("最低电压", cellVoltageText(cells?.minimumMv), Modifier.weight(1f))
        ChargeHeroMetric(
            "压差",
            deltaMv?.let { "$it mV" } ?: "--",
            Modifier.weight(1f),
            valueColor = deltaColor
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        buildString {
            append("${info.cellCount.coerceAtLeast(millivolts.size)} 节")
            info.balancingCurrentMa?.let { append("  ·  均衡电流 $it mA") }
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp
    )
}

@Composable
//MARK:参数分组
//ParamGroup 绘制一组同类保护参数，包含分组标题及内部参数单元格。
private fun ParamGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 16.sp
            )
            val subtitleIndent = with(LocalDensity.current) { (11.sp * 2).toDp() }
            Column(
                modifier = Modifier.padding(start = subtitleIndent),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
//MARK:参数对
//ParamPair 把两个相关保护阈值并排展示，空间不足时保持一致宽度。
private fun ParamPair(
    leftLabel: String,
    leftValue: String,
    rightLabel: String?,
    rightValue: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(56.dp)
    ) {
        ParamCell(leftLabel, leftValue, Modifier.weight(1f))
        if (rightLabel != null && rightValue != null) {
            ParamCell(rightLabel, rightValue, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
//MARK:保护参数格
//ParamCell 绘制单体组件，并根据参数决定文案、数值、颜色及交互状态。
private fun ParamCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            maxLines = 1
        )
    }
}

@Composable
//MARK:刷新按钮
//绘制紧凑的参数刷新按钮；加载期间显示旋转进度并禁止重复点击。
private fun CompactRefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    OutlinedButton(
        onClick = onRefresh,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text(if (loading) "正在读取…" else "重新读取", fontSize = 12.sp)
    }
}

//MARK:电压文字
//把可空电压阈值格式化为伏特文本，未知参数显示占位符。
private fun voltageText(value: Double?): String =
    value?.let { format(it, "V", if (it >= 10.0) 2 else 3) } ?: "--"

//MARK:单体电压文字
//cellVoltageText 绘制单体组件，并根据参数决定文案、数值、颜色及交互状态。
private fun cellVoltageText(millivolts: Int?): String =
    millivolts?.let { format(it / 1000.0, "V", 3) } ?: "--"

//MARK:电流文字
//currentText 将当前转换为适合当前页面展示的文本，并统一精度、单位或正负号格式。
private fun currentText(value: Double?): String =
    value?.let { format(it, "A", 2) } ?: "--"

//MARK:温度文字
//把可空温度阈值格式化为摄氏度文本，未知参数显示占位符。
private fun tempText(value: Double?): String =
    value?.let { format(it, "℃", 1) } ?: "--"

@Preview(name = "保护参数", showBackground = true, widthDp = 392, heightDp = 850)
@Composable
//MARK:保护参数预览
//ProtectionParamsPreview 使用固定演示数据预览保护参数布局，只参与调试构建而不读取真实设备。
private fun ProtectionParamsPreview() {
    JbdBmsTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ProtectionParamsPage(state = demoBmsState(), onRefresh = {})
        }
    }
}
