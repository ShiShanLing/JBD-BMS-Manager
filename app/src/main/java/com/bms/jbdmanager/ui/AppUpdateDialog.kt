package com.bms.jbdmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.update.AppUpdateEntry
import com.bms.jbdmanager.update.AppUpdateState

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onUpdate: () -> Unit
) {
    val info = state.available ?: return
    val force = info.forceUpdate
    AlertDialog(
        onDismissRequest = { if (!force && !state.downloading) onDismiss() },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "当前版本 ${state.currentVersionName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                ChangelogSection(info.notesSince(state.currentVersionCode))
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Text(
                        "正在下载 ${state.progressPercent}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else if (state.apkFilePath != null) {
                    Text("安装包已就绪，点击后开始安装。", fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            if (!force) {
                TextButton(onClick = onSkip, enabled = !state.downloading) { Text("跳过此版本") }
            }
        },
        confirmButton = {
            Row {
                if (!force && !state.downloading) {
                    TextButton(onClick = onDismiss) { Text("稍后再说") }
                }
                TextButton(onClick = onUpdate, enabled = !state.downloading) {
                    Text(
                        when {
                            state.downloading -> "下载中"
                            state.apkFilePath != null -> "安装更新"
                            else -> "立即更新"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    )
}

@Composable
internal fun AppVersionDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onCheck: () -> Unit
) {
    val latest = state.latest
    val hasNewer = state.hasNewerVersion
    val checkError = state.checkError
    val alreadyLatest = latest != null && !hasNewer && checkError == null
    val needCheck = !state.checking && !hasNewer && !alreadyLatest
    val status = when {
        state.checking -> "正在获取服务器版本…"
        checkError != null -> "获取失败：$checkError"
        latest == null -> "尚未获取服务器版本"
        hasNewer -> "发现新版本 ${latest.versionName}"
        else -> "当前已是最新版本"
    }
    val statusColor = when {
        state.checking -> MaterialTheme.colorScheme.onSurfaceVariant
        checkError != null -> MaterialTheme.colorScheme.error
        hasNewer -> MaterialTheme.colorScheme.secondary
        latest != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AlertDialog(
        onDismissRequest = { if (!state.downloading) onDismiss() },
        title = { Text("App 版本") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VersionInfoRow("当前版本", "v${state.currentVersionName}")
                VersionInfoRow("内部版本号", state.currentVersionCode.toString())
                VersionInfoRow(
                    "服务器版本",
                    latest?.let { "v${it.versionName}（${it.versionCode}）" } ?: "未获取"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, color = statusColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (needCheck) {
                        TextButton(
                            onClick = onCheck,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("检查更新", fontSize = 12.sp) }
                    }
                }
                val notes = latest?.let { info ->
                    if (hasNewer) {
                        info.notesSince(state.currentVersionCode)
                    } else {
                        listOf(
                            AppUpdateEntry(info.versionCode, info.versionName, info.releaseNotes)
                        ).filter { it.releaseNotes.isNotBlank() }
                    }
                }.orEmpty()
                if (notes.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    Text("更新说明", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    ChangelogSection(notes)
                }
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Text(
                        "正在下载 ${state.progressPercent}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else if (hasNewer && state.apkFilePath != null) {
                    Text("安装包已就绪，正在打开安装界面。", fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.downloading) { Text("关闭") }
        },
        confirmButton = {
            if (hasNewer) {
                TextButton(onClick = onUpdate, enabled = !state.downloading) {
                    Text(
                        when {
                            state.downloading -> "下载中"
                            state.apkFilePath != null -> "安装更新"
                            else -> "立即更新"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    )
}

@Composable
private fun ChangelogSection(entries: List<AppUpdateEntry>) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "v${entry.versionName}",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(entry.releaseNotes, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun VersionInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.width(88.dp)
        )
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
