package com.bms.jbdmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bms.jbdmanager.R
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.ui.theme.JbdBmsTheme

@Composable
internal fun Dashboard(
    state: BmsUiState,
    onShowDevices: () -> Unit,
    onSubmitPassword: (String) -> Boolean,
    onRequestLocationPermission: () -> Unit,
    onRequestExit: () -> Unit,
    onArmBrakeTest: (Int) -> Unit,
    onCancelBrakeTest: () -> Unit,
    onClearSpeedRangeStats: () -> Unit,
    onShowAppVersion: () -> Unit,
    onRefreshProtectionParams: () -> Unit,
    onEnterPictureInPicture: () -> Unit = {},
    isPreview: Boolean = false,
    initialTab: Int = 0
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeviceSummary(state, onShowDevices, onSubmitPassword, onRequestExit, onShowAppVersion, isPreview)
            DashboardTabSelector(tab, listOf("概览", "保护参数", "续航测试", "刹车测试")) { tab = it }
            when (tab) {
                0 -> Overview(state, onRequestLocationPermission)
                1 -> ProtectionParamsPage(state, onRefreshProtectionParams)
                2 -> RangeTestPage(state, onRequestLocationPermission, onClearSpeedRangeStats)
                else -> BrakeTestPage(state, onArmBrakeTest, onCancelBrakeTest)
            }
        }
        FloatingActionButton(
            onClick = onEnterPictureInPicture,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 16.dp)
                .zIndex(1f),
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            contentColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_picture_in_picture),
                contentDescription = "进入小窗",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
@Composable
private fun DeviceSummary(
    state: BmsUiState,
    onShowDevices: () -> Unit,
    onSubmitPassword: (String) -> Boolean,
    onRequestExit: () -> Unit,
    onShowAppVersion: () -> Unit,
    isPreview: Boolean
) {
    var showSettings by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(state.authenticationRequired) {
        if (state.authenticationRequired) showSettings = true
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("电池详情", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                buildString {
                    append(state.connectedName ?: "已连接 BMS")
                    state.lastValidDataAtMillis?.let {
                        append(" · ")
                        append(
                            if (state.dataFreshness == DataFreshness.Stale) {
                                "数据已过期（${state.lastDataAgeSeconds ?: 0}秒）"
                            } else {
                                "${state.lastDataAgeSeconds ?: 0}秒前更新"
                            }
                        )
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
        OutlinedButton(
            onClick = onShowDevices,
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) { Text(if (isPreview) "退出演示" else "设备", fontSize = 11.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = { showSettings = true },
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) { Text("设置", fontSize = 11.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onRequestExit,
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) { Text("退出", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
    if (showSettings) {
        DeviceSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onSubmitPassword = onSubmitPassword,
            onShowAppVersion = onShowAppVersion
        )
    }
}

@Composable
private fun DeviceSettingsDialog(
    state: BmsUiState,
    onDismiss: () -> Unit,
    onSubmitPassword: (String) -> Boolean,
    onShowAppVersion: () -> Unit
) {
    var password by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogInfoRow("BMS 型号", state.modelName ?: "未识别")
                DialogInfoRow("芯片方案", state.chipType ?: "未识别")
                DialogInfoRow("软件版本", state.basicInfo?.softwareVersion ?: "未读取")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                DialogInfoRow("蓝牙设备", state.connectedName ?: "未命名设备")
                DialogInfoRow("蓝牙地址", state.connectedAddress ?: "--")
                DialogInfoRow("BLE 通道", state.protocolProfile)
                DialogInfoRow("识别协议", state.detectedProtocol ?: "等待有效BMS响应")
                state.bleChannelDetails?.let { DialogInfoRow("连接诊断", it) }
                DialogInfoRow("操作模式", "安全只读")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                DialogInfoRow("App 版本", "v${state.appUpdate.currentVersionName}（${state.appUpdate.currentVersionCode}）")
                if (state.appUpdate.hasNewerVersion) {
                    Text(
                        "发现新版本 ${state.appUpdate.latest?.versionName}",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onShowAppVersion()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看 App 版本")
                }
                if (state.authenticationRequired) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    Text(
                        state.authenticationMessage ?: "此蓝牙模块需要身份认证",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { value -> password = value.filter(Char::isDigit).take(6) },
                        label = { Text("6位蓝牙读取密码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (onSubmitPassword(password)) password = ""
                        },
                        enabled = password.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("进行只读认证") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DialogInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun DashboardTabSelector(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(index) },
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 5.dp), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PreviewDashboard(initialTab: Int) {
    JbdBmsTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Dashboard(
                state = demoBmsState(),
                onShowDevices = {},
                onSubmitPassword = { false },
                onRequestLocationPermission = {},
                onRequestExit = {},
                onArmBrakeTest = {},
                onCancelBrakeTest = {},
                onClearSpeedRangeStats = {},
                onShowAppVersion = {},
                onRefreshProtectionParams = {},
                isPreview = true,
                initialTab = initialTab
            )
        }
    }
}

@Preview(name = "详情-概览", showBackground = true, widthDp = 392, heightDp = 850)
@Composable
private fun DashboardOverviewPreview() {
    PreviewDashboard(initialTab = 0)
}

@Preview(name = "详情-保护参数", showBackground = true, widthDp = 392, heightDp = 850)
@Composable
private fun DashboardProtectionParamsPreview() {
    PreviewDashboard(initialTab = 1)
}
