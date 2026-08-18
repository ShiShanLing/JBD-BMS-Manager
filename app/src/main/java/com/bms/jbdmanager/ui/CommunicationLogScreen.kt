package com.bms.jbdmanager.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.RawLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LogsPanel(logs: List<RawLogEntry>, onClear: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Text(
            "连接成功后分享日志，其中“连接诊断”和“协议识别结果”可用于确认你的BMS协议。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("保留最近 ${logs.size} 条", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), fontSize = 12.sp)
            OutlinedButton(
                onClick = {
                    val body = logs.asReversed().joinToString("\n") { entry ->
                        "${time(entry.timestampMillis)} ${entry.direction.name.uppercase()} ${entry.note}" +
                            if (entry.hex.isBlank()) "" else "\n${entry.hex}"
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "JBD BMS 通信日志")
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享通信日志"))
                },
                enabled = logs.isNotEmpty()
            ) { Text("分享") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClear) { Text("清空") }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun LogRow(entry: RawLogEntry) {
    val color = when (entry.direction) {
        RawLogEntry.Direction.Tx -> MaterialTheme.colorScheme.secondary
        RawLogEntry.Direction.Rx -> MaterialTheme.colorScheme.primary
        RawLogEntry.Direction.Error -> MaterialTheme.colorScheme.error
        RawLogEntry.Direction.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(time(entry.timestampMillis), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(entry.direction.name.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(entry.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            if (entry.hex.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(entry.hex, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun time(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(timeFormatter)
