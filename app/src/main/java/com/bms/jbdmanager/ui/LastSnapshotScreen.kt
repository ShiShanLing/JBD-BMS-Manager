package com.bms.jbdmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.LastBmsSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LastSnapshotScreen(snapshot: LastBmsSnapshot, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
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
        Overview(
            state = snapshot.asUiState(),
            onRequestLocationPermission = {}
        )
    }
}

private val snapshotTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatSnapshotTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(snapshotTimeFormatter)
