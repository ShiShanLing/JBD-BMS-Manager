package com.bms.jbdmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.LastBmsSnapshot
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.BatteryTrendRange
import com.bms.jbdmanager.model.BatteryTrendState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LastSnapshotScreen(
    snapshot: LastBmsSnapshot,
    capacityHealthRecords: List<CapacityHealthRecord>,
    protectionEvents: List<ProtectionEvent>,
    batteryTrend: BatteryTrendState,
    onLoadBatteryTrend: (BatteryTrendRange) -> Unit,
    onAddCapacityHealthRecord: (Double, Double?, String) -> Unit,
    onDeleteCapacityHealthRecord: (Long) -> Unit,
    onStartAutomaticCapacityTest: () -> Unit,
    onFinishAutomaticCapacityTest: () -> Unit,
    onDiscardAutomaticCapacityTest: () -> Unit,
    onSaveAutomaticCapacityTestResult: () -> Unit,
    onShowDataManagement: () -> Unit,
    onBack: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var historySubpageOpen by remember { mutableStateOf(false) }
    var showFullChargeStats by remember { mutableStateOf(false) }
    BackHandler(onBack = {
        if (showFullChargeStats) showFullChargeStats = false else onBack()
    })
    Column(Modifier.fillMaxSize()) {
        if (!historySubpageOpen && !showFullChargeStats) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("最后一次状态", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "${snapshot.deviceName ?: "未命名设备"} · ${formatSnapshotTime(snapshot.savedAtMillis)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text("返回", fontSize = 11.sp)
                }
            }
            Text(
                "离线保存数据，不会实时更新",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        val snapshotState = snapshot.asUiState().copy(
            capacityHealthRecords = capacityHealthRecords,
            protectionEvents = protectionEvents,
            batteryTrend = batteryTrend
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showFullChargeStats) {
                FullChargeStatsDestination(
                    state = snapshotState,
                    onLoadBatteryTrend = onLoadBatteryTrend,
                    onBack = { showFullChargeStats = false }
                )
            } else when (tab) {
                0 -> Overview(
                    state = snapshotState,
                    onRequestLocationPermission = {},
                    onOpenFullChargeStats = { showFullChargeStats = true }
                )
                1 -> ProtectionParamsPage(
                    state = snapshotState,
                    onRefresh = {},
                    readOnly = true
                )
                2 -> RangeTestPage(
                    state = snapshotState,
                    onRequestLocationPermission = {},
                    onClearSpeedRangeStats = {},
                    readOnly = true
                )
                else -> BatteryHistoryPage(
                    state = snapshotState,
                    onLoadBatteryTrend = onLoadBatteryTrend,
                    onAddCapacityRecord = onAddCapacityHealthRecord,
                    onDeleteCapacityRecord = onDeleteCapacityHealthRecord,
                    onStartAutomaticCapacityTest = onStartAutomaticCapacityTest,
                    onFinishAutomaticCapacityTest = onFinishAutomaticCapacityTest,
                    onDiscardAutomaticCapacityTest = onDiscardAutomaticCapacityTest,
                    onSaveAutomaticCapacityTestResult = onSaveAutomaticCapacityTestResult,
                    onShowDataManagement = onShowDataManagement,
                    onSubpageChanged = { historySubpageOpen = it }
                )
            }
        }
        if (!historySubpageOpen && !showFullChargeStats) {
            DashboardBottomNavigation(selected = tab, onSelect = {
                tab = it
                if (it != 3) historySubpageOpen = false
            })
        }
    }
}

private val snapshotTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatSnapshotTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(snapshotTimeFormatter)
