package com.bms.jbdmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.BatteryTrendRange
import com.bms.jbdmanager.model.FullChargeDeltaDirection
import com.bms.jbdmanager.model.HealthDiagnosisLevel
import com.bms.jbdmanager.model.diagnoseBatteryHealth
import com.bms.jbdmanager.model.evaluateFullChargeDeltaTrend
import java.util.Locale

private enum class HistoryDestination(val title: String) {
    Mileage("行程记录"), Protection("告警记录"), Capacity("容量健康"),
    FullChargeStats("满充统计"), Trend("电池趋势"), Diagnosis("健康诊断")
}

private data class HistoryMenuEntry(
    val title: String,
    val summary: String,
    val accent: Color,
    val destination: HistoryDestination? = null
)

@Composable
internal fun BatteryHistoryPage(
    state: BmsUiState,
    onLoadBatteryTrend: (BatteryTrendRange) -> Unit,
    onAddCapacityRecord: (Double, Double?, String) -> Unit,
    onDeleteCapacityRecord: (Long) -> Unit,
    onStartAutomaticCapacityTest: () -> Unit,
    onFinishAutomaticCapacityTest: () -> Unit,
    onDiscardAutomaticCapacityTest: () -> Unit,
    onSaveAutomaticCapacityTestResult: () -> Unit,
    onShowDataManagement: () -> Unit,
    onSubpageChanged: (Boolean) -> Unit,
    openFullChargeStats: Boolean = false
) {
    var category by rememberSaveable { mutableIntStateOf(if (openFullChargeStats) 1 else 0) }
    var destinationOrdinal by rememberSaveable {
        mutableIntStateOf(if (openFullChargeStats) HistoryDestination.FullChargeStats.ordinal else -1)
    }
    val destination = HistoryDestination.entries.getOrNull(destinationOrdinal)
    BackHandler(enabled = destination != null) { destinationOrdinal = -1 }
    LaunchedEffect(destination) { onSubpageChanged(destination != null) }

    Column(Modifier.fillMaxSize()) {
        if (destination == null) {
            DashboardTabSelector(category, listOf("记录", "健康", "数据")) {
                category = it
                destinationOrdinal = -1
            }
            when (category) {
                0 -> RecordHistoryMenu(state) { destinationOrdinal = it.ordinal }
                1 -> HealthHistoryMenu(state) { destinationOrdinal = it.ordinal }
                else -> DataHistoryMenu(state, onShowDataManagement)
            }
        } else {
            HistoryDetailHeader(destination.title) { destinationOrdinal = -1 }
            when (destination) {
                HistoryDestination.Mileage -> MileageHistoryPage(state.mileageHistory)
                HistoryDestination.Protection -> ProtectionHistoryPage(state.protectionEvents)
                HistoryDestination.Capacity -> CapacityHealthPage(
                    state,
                    onAddCapacityRecord,
                    onDeleteCapacityRecord,
                    onStartAutomaticCapacityTest,
                    onFinishAutomaticCapacityTest,
                    onDiscardAutomaticCapacityTest,
                    onSaveAutomaticCapacityTestResult
                )
                HistoryDestination.FullChargeStats -> FullChargeDeltaStatsPage(
                    samples = state.batteryTrend.fullChargeDeltas,
                    onLoad = { onLoadBatteryTrend(state.batteryTrend.range) }
                )
                HistoryDestination.Trend -> BatteryTrendPage(state.batteryTrend, onLoadBatteryTrend)
                HistoryDestination.Diagnosis -> BatteryHealthDiagnosisPage(state)
            }
        }
    }
}

@Composable
private fun RecordHistoryMenu(state: BmsUiState, open: (HistoryDestination) -> Unit) {
    val trips = state.mileageHistory.sessions
    val totalKm = trips.sumOf { it.distanceMeters } / 1_000.0
    val activeAlerts = state.protectionEvents.count { it.isActive }
    HistoryMenu(
        heading = "使用与异常记录",
        description = "查看骑行里程，以及BMS实际触发和解除的保护事件。",
        entries = listOf(
            HistoryMenuEntry("行程记录", "${trips.size}次行程 · 累计 ${historyNumber(totalKm)} km", MaterialTheme.colorScheme.primary, HistoryDestination.Mileage),
            HistoryMenuEntry(
                "告警记录",
                if (activeAlerts > 0) "$activeAlerts 项触发中 · 共 ${state.protectionEvents.size} 条记录" else "当前正常 · 共 ${state.protectionEvents.size} 条记录",
                if (activeAlerts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                HistoryDestination.Protection
            )
        )
    ) { it.destination?.let(open) }
}

@Composable
private fun HealthHistoryMenu(state: BmsUiState, open: (HistoryDestination) -> Unit) {
    val formalRecords = state.capacityHealthRecords.filter { it.qualifiedForHealth }
    val latestCapacity = formalRecords.maxByOrNull { it.recordedAtMillis }
    val diagnosis = rememberHealthDiagnosis(state)
    HistoryMenu(
        heading = "电池健康",
        description = "容量、运行趋势和长期单体变化集中在这里，后续新增功能也不会增加顶部标签。",
        entries = listOf(
            HistoryMenuEntry(
                "容量健康",
                latestCapacity?.let { "最新 ${historyNumber(it.measuredDischargeAh)} Ah · SOH ${historyNumber(it.sohPercent)}% · ${formalRecords.size}次正式测试" }
                    ?: "尚无完整容量测试记录",
                latestCapacity?.let { healthMenuColor(it.sohPercent) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                HistoryDestination.Capacity
            ),
            HistoryMenuEntry(
                "满充统计",
                fullChargeDeltaMenuSummary(state),
                fullChargeDeltaMenuColor(state),
                HistoryDestination.FullChargeStats
            ),
            HistoryMenuEntry(
                "电池趋势",
                "${state.batteryTrend.points.size}个当前图表点 · ${state.batteryTrend.fullChargeFingerprints.size}次满充单体记录",
                MaterialTheme.colorScheme.primary,
                HistoryDestination.Trend
            ),
            HistoryMenuEntry(
                "健康诊断",
                "${diagnosis.summary} · ${diagnosis.capacityTestCount}次容量测试",
                diagnosisMenuColor(diagnosis.overallLevel),
                HistoryDestination.Diagnosis
            )
        )
    ) { it.destination?.let(open) }
}

@Composable
private fun DataHistoryMenu(state: BmsUiState, onShowDataManagement: () -> Unit) {
        val localRecordCount = state.mileageHistory.sessions.size + state.capacityHealthRecords.size +
        state.protectionEvents.size + state.batteryTrend.fullChargeFingerprints.size +
        state.batteryTrend.fullChargeDeltas.size
    HistoryMenu(
        heading = "数据与报告",
        description = "集中管理本地资料。备份、CSV和PDF使用系统文件选择器，不需要额外存储权限。",
        entries = listOf(
            HistoryMenuEntry("完整备份与恢复", "保护全部本地记录，恢复前自动校验", MaterialTheme.colorScheme.primary),
            HistoryMenuEntry("CSV 原始资料", "导出趋势、容量、告警和行程等原始表格", MaterialTheme.colorScheme.secondary),
            HistoryMenuEntry("电池健康 PDF", "App内预览，也可保存或分享", MaterialTheme.colorScheme.primary),
            HistoryMenuEntry("本地数据概况", "已保存约 $localRecordCount 组记录，不会上传服务器", MaterialTheme.colorScheme.onSurfaceVariant)
        )
    ) { onShowDataManagement() }
}

@Composable
private fun HistoryMenu(
    heading: String,
    description: String,
    entries: List<HistoryMenuEntry>,
    onEntryClick: (HistoryMenuEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
                Text(heading, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        entries.forEach { entry ->
            item(key = entry.title) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onEntryClick(entry) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = entry.accent)
                            Text(entry.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Text("›", color = entry.accent, fontSize = 26.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryDetailHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text("‹", fontSize = 30.sp, lineHeight = 30.sp)
            Spacer(Modifier.width(3.dp))
            Text("返回", fontSize = 12.sp)
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BatteryHealthDiagnosisPage(state: BmsUiState) {
    val diagnosis = rememberHealthDiagnosis(state)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { BatteryHealthDiagnosisCard(diagnosis) }
        item {
            Text(
                "诊断基于本地长期记录；数据不足时不会强行给出健康结论。完整证据可在“数据”中生成PDF报告。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun rememberHealthDiagnosis(state: BmsUiState) = remember(
    state.capacityHealthRecords,
    state.batteryTrend.fullChargeFingerprints,
    state.batteryTrend.fullChargeDeltas,
    state.protectionEvents,
    state.connectedAddress,
    state.lastSnapshot?.deviceAddress
) {
    diagnoseBatteryHealth(
        state.capacityHealthRecords,
        state.batteryTrend.fullChargeFingerprints,
        state.protectionEvents,
        state.connectedAddress ?: state.lastSnapshot?.deviceAddress,
        state.batteryTrend.fullChargeDeltas
    )
}

@Composable
private fun healthMenuColor(soh: Double): Color = when {
    soh < 75.0 -> MaterialTheme.colorScheme.error
    soh < 80.0 -> Color(0xFFFF8A3D)
    soh < 90.0 -> Color(0xFFFFC107)
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun diagnosisMenuColor(level: HealthDiagnosisLevel): Color = when (level) {
    HealthDiagnosisLevel.Critical -> MaterialTheme.colorScheme.error
    HealthDiagnosisLevel.Warning -> Color(0xFFFF8A3D)
    HealthDiagnosisLevel.Observe -> Color(0xFFFFC107)
    HealthDiagnosisLevel.Normal -> MaterialTheme.colorScheme.primary
    HealthDiagnosisLevel.Insufficient -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun fullChargeDeltaMenuColor(state: BmsUiState): Color {
    val direction = evaluateFullChargeDeltaTrend(state.batteryTrend.fullChargeDeltas).direction
    return when (direction) {
        FullChargeDeltaDirection.Improving, FullChargeDeltaDirection.Stable -> MaterialTheme.colorScheme.primary
        FullChargeDeltaDirection.Worsening -> Color(0xFFFF8A3D)
        FullChargeDeltaDirection.Insufficient -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun fullChargeDeltaMenuSummary(state: BmsUiState): String {
    val trend = evaluateFullChargeDeltaTrend(state.batteryTrend.fullChargeDeltas)
    val count = trend.samples.size
    val verdict = when (trend.direction) {
        FullChargeDeltaDirection.Improving -> "向好"
        FullChargeDeltaDirection.Stable -> "基本稳定"
        FullChargeDeltaDirection.Worsening -> "变差"
        FullChargeDeltaDirection.Insufficient -> "待积累"
    }
    return when {
        count == 0 -> "尚无满充压差记录"
        else -> "$count 次满充压差 · $verdict" +
            (trend.latestDeltaMv?.let { " · 最新 ${it}mV" } ?: "")
    }
}

private fun historyNumber(value: Double): String =
    String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
