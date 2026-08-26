package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.ProtectionEventSeverity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

@Composable
internal fun ProtectionHistoryPage(events: List<ProtectionEvent>) {
    val sorted = events.sortedByDescending { it.startedAtMillis }
    val activeCount = sorted.count { it.isActive }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("保护告警记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            if (activeCount > 0) "$activeCount 项触发中" else "当前正常",
                            color = if (activeCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "记录 App 连接期间 BMS 实际上报的保护变化；离线期间发生的事件无法补录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
        if (sorted.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无保护告警", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "只有 BMS 真实触发或解除保护时才会生成记录。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        } else {
            items(sorted, key = { it.id }) { event -> ProtectionEventCard(event) }
        }
    }
}

@Composable
private fun ProtectionEventCard(event: ProtectionEvent) {
    val color = protectionSeverityColor(event.severity)
    Card(shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(color, CircleShape))
                Spacer(Modifier.size(7.dp))
                Text(event.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (event.isActive) "触发中" else "已解除",
                    color = if (event.isActive) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(event.summary, color = color, fontSize = 11.sp, lineHeight = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    append(formatProtectionTime(event.startedAtMillis))
                    event.resolvedAtMillis?.let {
                        append(" · 持续 ")
                        append(formatProtectionDuration(it - event.startedAtMillis))
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                buildList {
                    add("SOC ${event.stateOfChargePercent}%")
                    add("${compactNumber(event.totalVoltageV)}V")
                    add("${signedCurrent(event.currentA)}A")
                    event.minimumCellMv?.let { add("最低 ${it}mV") }
                    event.cellDeltaMv?.let { add("压差 ${it}mV") }
                    event.maximumTemperatureC?.let { add("最高温 ${compactNumber(it, 1)}℃") }
                }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun protectionSeverityColor(severity: ProtectionEventSeverity): Color = when (severity) {
    ProtectionEventSeverity.Expected -> Color(0xFFFFC857)
    ProtectionEventSeverity.Warning -> AlertOrange
    ProtectionEventSeverity.Critical -> MaterialTheme.colorScheme.error
}

private fun signedCurrent(currentA: Double): String = if (currentA > 0.0) "+${compactNumber(currentA)}" else compactNumber(currentA)

private fun formatProtectionDuration(durationMillis: Long): String {
    val seconds = (durationMillis.coerceAtLeast(0L) / 1_000.0).roundToLong()
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3_600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3_600}小时${seconds % 3_600 / 60}分"
    }
}

private val protectionEventTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatProtectionTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(protectionEventTimeFormatter)
