package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.AutomaticCapacityTestPhase
import com.bms.jbdmanager.model.AutomaticCapacityTestState
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.CapacityHealthRecordSource
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.diagnoseBatteryHealth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CapacityHealthPage(
    state: BmsUiState,
    onAddRecord: (Double, Double?, String) -> Unit,
    onDeleteRecord: (Long) -> Unit,
    onStartAutomaticTest: () -> Unit,
    onFinishAutomaticTest: () -> Unit,
    onDiscardAutomaticTest: () -> Unit,
    onSaveAutomaticTestResult: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteRecord by remember { mutableStateOf<CapacityHealthRecord?>(null) }
    val records = state.capacityHealthRecords.sortedByDescending { it.recordedAtMillis }
    val qualifiedRecords = records.filter { it.qualifiedForHealth }
    val latest = qualifiedRecords.firstOrNull()
    val ratedCapacityAh = state.basicInfo?.nominalCapacityAh?.takeIf { it > 0.0 }
    val diagnosis = remember(
        state.capacityHealthRecords,
        state.batteryTrend.fullChargeFingerprints,
        state.protectionEvents,
        state.connectedAddress,
        state.lastSnapshot?.deviceAddress
    ) {
        diagnoseBatteryHealth(
            capacityRecords = state.capacityHealthRecords,
            fingerprints = state.batteryTrend.fullChargeFingerprints,
            protectionEvents = state.protectionEvents,
            deviceAddress = state.connectedAddress ?: state.lastSnapshot?.deviceAddress
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AutomaticCapacityTestCard(
                test = state.automaticCapacityTest,
                isConnected = state.phase == ConnectionPhase.Ready && state.basicInfo != null &&
                    (state.automaticCapacityTest.deviceAddress == null ||
                        state.automaticCapacityTest.deviceAddress == state.connectedAddress),
                onStart = onStartAutomaticTest,
                onFinish = onFinishAutomaticTest,
                onDiscard = onDiscardAutomaticTest,
                onSave = onSaveAutomaticTestResult
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
                ),
                shape = RoundedCornerShape(15.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("容量健康档案", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                ratedCapacityAh?.let {
                                    "当前以 BMS 总容量 ${compactNumber(it)}Ah 为计算基准"
                                } ?: "尚未读取到 BMS 总容量",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = { showAddDialog = true },
                            enabled = ratedCapacityAh != null
                        ) { Text("记录测试") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HealthSummary(
                            label = "最新实测容量",
                            value = latest?.let { "${compactNumber(it.measuredDischargeAh)} Ah" } ?: "暂无",
                            modifier = Modifier.weight(1f)
                        )
                        HealthSummary(
                            label = "实测健康度",
                            value = latest?.let { "${compactNumber(it.sohPercent, 1)}%" } ?: "暂无",
                            modifier = Modifier.weight(1f),
                            valueColor = latest?.let { healthAlertColor(it.sohPercent) }
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        state.basicInfo?.estimatedSohPercent?.let {
                            "BMS 当前估算 ${compactNumber(it, 1)}% · 仅作参考，与实测容量分开记录"
                        } ?: "BMS 未提供满充容量，健康度以容量测试记录为准",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        item { BatteryHealthDiagnosisCard(diagnosis) }

        if (records.isNotEmpty()) {
            if (qualifiedRecords.isNotEmpty()) {
                item { CapacityHealthTrend(qualifiedRecords.take(8).reversed()) }
            }
            item {
                Text("容量测试记录", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
            items(records.size, key = { records[it].id }) { index ->
                CapacityHealthRecordCard(records[index], onDelete = { deleteRecord = records[index] })
            }
        } else {
            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有容量测试记录", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "完成一次从满电到低压保护的容量测试后，填写实际放出的 Ah；可选填写 Wh 和备注。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCapacityRecordDialog(
            ratedCapacityAh = ratedCapacityAh ?: return,
            onDismiss = { showAddDialog = false },
            onConfirm = { ah, wh, note ->
                onAddRecord(ah, wh, note)
                showAddDialog = false
            }
        )
    }
    deleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteRecord = null },
            title = { Text("删除容量记录？") },
            text = { Text("将删除 ${formatCapacityDate(record.recordedAtMillis)} 的 ${compactNumber(record.measuredDischargeAh)}Ah 测试记录。") },
            dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecord(record.id)
                    deleteRecord = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun AutomaticCapacityTestCard(
    test: AutomaticCapacityTestState,
    isConnected: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动容量测试", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    when (test.phase) {
                        AutomaticCapacityTestPhase.Idle -> "等待"
                        AutomaticCapacityTestPhase.Running -> if (isConnected) "记录中" else "等待重连"
                        AutomaticCapacityTestPhase.Completed -> if (test.isQualifiedForHealth) "正式结果" else "参考结果"
                    },
                    color = when {
                        test.phase == AutomaticCapacityTestPhase.Completed && !test.isQualifiedForHealth -> MaterialTheme.colorScheme.tertiary
                        test.phase == AutomaticCapacityTestPhase.Completed -> MaterialTheme.colorScheme.primary
                        test.phase == AutomaticCapacityTestPhase.Running -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(5.dp))
            when (test.phase) {
                AutomaticCapacityTestPhase.Idle -> {
                    Text(
                        "满电且 SOC≥98% 时自动开始；从实时电流累计放电量，达到低电量或低压保护后自动结束。断线后会续测。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onStart, enabled = isConnected, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isConnected) "立即开始" else "连接 BMS 后可开始")
                    }
                }
                AutomaticCapacityTestPhase.Running -> {
                    CapacityTestMetricRow(
                        "SOC",
                        "${test.startSocPercent ?: 0}% → ${test.currentSocPercent ?: 0}%",
                        "已放出",
                        "${compactNumber(test.measuredDischargeAh, 2)} Ah"
                    )
                    CapacityTestMetricRow(
                        "累计电量",
                        "${compactNumber(test.dischargedWh, 0)} Wh",
                        "数据覆盖",
                        "${compactNumber(test.coveragePercent, 1)}%"
                    )
                    CapacityTestMetricRow(
                        "运行时间",
                        formatTestDuration(test.validDurationSeconds),
                        "断线缺口",
                        "${compactNumber(test.missingDischargeAh, 2)} Ah"
                    )
                    Text(
                        if (isConnected) "正在根据 BMS 实时电流积分，退出 App 或短暂断线不会清空。"
                        else "蓝牙已断开，测试已保存；重新连接后会从当前容量继续补记。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("取消测试") }
                        Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("结束测试") }
                    }
                }
                AutomaticCapacityTestPhase.Completed -> {
                    CapacityTestMetricRow(
                        "实测容量",
                        "${compactNumber(test.measuredDischargeAh, 2)} Ah",
                        "估算 SOH",
                        test.ratedCapacityAh?.takeIf { it > 0.0 }?.let {
                            "${compactNumber(test.measuredDischargeAh / it * 100.0, 1)}%"
                        } ?: "--"
                    )
                    CapacityTestMetricRow(
                        "数据覆盖",
                        "${compactNumber(test.coveragePercent, 1)}%",
                        "断线缺口",
                        "${compactNumber(test.missingDischargeAh, 2)} Ah"
                    )
                    Text(test.resultExplanation, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    test.finishReason?.let {
                        Text("结束原因：$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("丢弃") }
                        Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                            Text(if (test.isQualifiedForHealth) "保存正式记录" else "保存参考记录")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapacityTestMetricRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$leftLabel  $leftValue", fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("$rightLabel  $rightValue", fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

private fun formatTestDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}

@Composable
private fun HealthSummary(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    Box(modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(11.dp))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(value, color = valueColor ?: MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CapacityHealthTrend(records: List<CapacityHealthRecord>) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("实测健康度趋势", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(118.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                records.forEach { record ->
                    val barColor = healthAlertColor(record.sohPercent) ?: MaterialTheme.colorScheme.primary
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text("${compactNumber(record.sohPercent, 0)}%", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .width(18.dp)
                                .height((record.sohPercent.coerceIn(0.0, 110.0) / 110.0 * 72.0).dp)
                                .background(barColor, RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(formatCapacityShortDate(record.recordedAtMillis), fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapacityHealthRecordCard(record: CapacityHealthRecord, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatCapacityDate(record.recordedAtMillis), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            record.source == CapacityHealthRecordSource.Manual -> "手动记录"
                            record.qualifiedForHealth -> "自动测试 · 正式"
                            else -> "自动测试 · 仅供参考"
                        },
                        color = if (record.qualifiedForHealth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        fontSize = 9.sp
                    )
                    Text(
                        "${compactNumber(record.measuredDischargeAh)} Ah · SOH ${compactNumber(record.sohPercent, 1)}%",
                        color = healthAlertColor(record.sohPercent) ?: MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                    Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            val details = buildList {
                add("基准 ${compactNumber(record.ratedCapacityAh)}Ah")
                record.measuredDischargeWh?.let { add("${compactNumber(it, 0)} Wh") }
                record.cycleCount?.let { add("循环 $it") }
                record.averageTemperatureC?.let { add("${compactNumber(it, 1)}℃") }
            }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            if (record.note.isNotBlank()) {
                Text(record.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun AddCapacityRecordDialog(
    ratedCapacityAh: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double?, String) -> Unit
) {
    var capacityText by remember { mutableStateOf("") }
    var energyText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val capacity = capacityText.toDoubleOrNull()
    val energy = energyText.toDoubleOrNull()
    val valid = capacity != null && capacity > 0.0 && capacity <= ratedCapacityAh * 1.5 &&
        (energyText.isBlank() || energy != null && energy > 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录容量测试") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "以 BMS 读取到的总容量 ${compactNumber(ratedCapacityAh)}Ah 为基准。请填写从满充到BMS低压保护全过程实际累计放出的容量。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                OutlinedTextField(
                    value = capacityText,
                    onValueChange = { capacityText = decimalInput(it, 6) },
                    label = { Text("实测放出容量（Ah）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = energyText,
                    onValueChange = { energyText = decimalInput(it, 7) },
                    label = { Text("实测放出电量（Wh，可选）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(100) },
                    label = { Text("备注（可选）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                capacity?.takeIf { it > 0.0 }?.let {
                    Text(
                        "计算结果：${compactNumber(it / ratedCapacityAh * 100.0, 1)}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = { onConfirm(capacity!!, energy, note) }, enabled = valid) { Text("保存") }
        }
    )
}

private fun decimalInput(value: String, maxLength: Int): String {
    val filtered = value.filter { it.isDigit() || it == '.' }.take(maxLength)
    val dot = filtered.indexOf('.')
    return if (dot < 0) filtered else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

private val capacityDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val capacityShortDateFormatter = DateTimeFormatter.ofPattern("M/d")

private fun formatCapacityDate(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(capacityDateFormatter)

private fun formatCapacityShortDate(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(capacityShortDateFormatter)
