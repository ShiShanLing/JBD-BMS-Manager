package com.bms.jbdmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.DataManagementState

@Composable
internal fun DataManagementDialog(
    state: DataManagementState,
    onDismiss: () -> Unit,
    onCreateBackup: () -> Unit,
    onSelectRestore: () -> Unit,
    onExportCsv: () -> Unit,
    onPreviewHealthPdf: () -> Unit,
    onExportHealthPdf: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.working) onDismiss() },
        title = { Text("数据管理") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (state.working) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(state.operationLabel ?: "正在处理数据…", fontSize = 12.sp)
                    }
                } else {
                    Text("完整备份", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "保存设备、最后状态、行程、续航样本、容量测试、告警、趋势和满充单体记录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Button(onClick = onCreateBackup, modifier = Modifier.fillMaxWidth()) {
                        Text("创建完整备份")
                    }
                    OutlinedButton(onClick = onSelectRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("从备份恢复")
                    }
                    Text(
                        "恢复会先校验文件并再次确认；恢复前必须断开蓝牙。",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Text("表格资料", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "导出多个CSV表格，适合用Excel查看或作为健康诊断、质保沟通的原始依据。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                        Text("导出CSV资料包")
                    }
                    Button(onClick = onPreviewHealthPdf, modifier = Modifier.fillMaxWidth()) {
                        Text("直接查看健康报告")
                    }
                    OutlinedButton(onClick = onExportHealthPdf, modifier = Modifier.fillMaxWidth()) {
                        Text("保存电池健康PDF")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !state.working) { Text("关闭") }
        }
    )
}
