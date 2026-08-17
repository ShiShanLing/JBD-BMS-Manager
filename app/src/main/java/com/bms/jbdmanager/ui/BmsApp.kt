package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bms.jbdmanager.BmsViewModel
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.RawLogEntry
import com.bms.jbdmanager.model.ScanDevice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.content.Intent
import kotlin.math.abs

@Composable
fun BmsApp(
    viewModel: BmsViewModel,
    requestPermissions: () -> Unit,
    requestEnableBluetooth: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDashboard by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == ConnectionPhase.Idle || state.phase == ConnectionPhase.Error) {
            showDashboard = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
        ) {
            AppHeader(state)
            if (showDashboard && state.phase == ConnectionPhase.Ready) {
                Dashboard(
                    state = state,
                    onShowDevices = { showDashboard = false },
                    onClearLogs = viewModel::clearLogs,
                    onSubmitPassword = viewModel::submitBluetoothPassword
                )
            } else {
                ScanPanel(
                    state = state,
                    requestPermissions = requestPermissions,
                    requestEnableBluetooth = requestEnableBluetooth,
                    startScan = viewModel::startScan,
                    stopScan = viewModel::stopScan,
                    connect = viewModel::connect,
                    disconnect = viewModel::disconnect,
                    showDashboard = { showDashboard = true }
                )
            }
        }
    }
}

@Composable
private fun AppHeader(state: BmsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("B", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("JBD BMS", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("安全只读监控", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        StatusBadge(state)
    }
}

@Composable
private fun StatusBadge(state: BmsUiState) {
    val (text, color) = when (state.phase) {
        ConnectionPhase.Ready -> when (state.dataFreshness) {
            DataFreshness.Stale -> "数据过期" to MaterialTheme.colorScheme.error
            DataFreshness.Waiting -> "等待数据" to MaterialTheme.colorScheme.secondary
            DataFreshness.Fresh -> "实时" to MaterialTheme.colorScheme.primary
        }
        ConnectionPhase.Scanning -> "扫描中" to MaterialTheme.colorScheme.secondary
        ConnectionPhase.Connecting, ConnectionPhase.Discovering -> "连接中" to MaterialTheme.colorScheme.secondary
        ConnectionPhase.Reconnecting -> "重连中" to MaterialTheme.colorScheme.secondary
        ConnectionPhase.Disconnecting -> "断开中" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionPhase.Error -> "异常" to MaterialTheme.colorScheme.error
        ConnectionPhase.Idle -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.15f), shape = CircleShape) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
    }
}

@Composable
private fun ScanPanel(
    state: BmsUiState,
    requestPermissions: () -> Unit,
    requestEnableBluetooth: () -> Unit,
    startScan: () -> Unit,
    stopScan: () -> Unit,
    connect: (String) -> Unit,
    disconnect: () -> Unit,
    showDashboard: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("连接电池", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "自动识别嘉佰达蓝牙服务、硬件型号和协议能力。首次连接不会修改任何保护参数。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(18.dp))
            when {
                !state.bluetoothSupported -> InfoCard("此设备不支持低功耗蓝牙（BLE）", MaterialTheme.colorScheme.error)
                !state.permissionsGranted -> PrimaryAction("允许附近设备权限", requestPermissions)
                !state.bluetoothEnabled -> PrimaryAction("开启蓝牙", requestEnableBluetooth)
                state.phase == ConnectionPhase.Ready && state.authenticationRequired ->
                    PrimaryAction("输入蓝牙读取密码", showDashboard)
                state.phase == ConnectionPhase.Ready -> PrimaryAction("查看实时数据", showDashboard)
                state.phase == ConnectionPhase.Scanning -> OutlinedButton(onClick = stopScan, modifier = Modifier.fillMaxWidth()) {
                    Text("停止扫描")
                }
                state.phase == ConnectionPhase.Connecting || state.phase == ConnectionPhase.Discovering ->
                    InfoCard("正在连接设备，请在下方设备卡片查看进度", MaterialTheme.colorScheme.secondary)
                state.phase == ConnectionPhase.Reconnecting ->
                    InfoCard("连接已中断，${state.reconnectInSeconds ?: 0} 秒后自动重连", MaterialTheme.colorScheme.secondary)
                state.phase == ConnectionPhase.Disconnecting ->
                    InfoCard("正在断开设备…", MaterialTheme.colorScheme.onSurfaceVariant)
                else -> PrimaryAction("扫描附近的 BMS", startScan)
            }
        }

        if (state.permissionsGranted && state.bluetoothEnabled) {
            if (state.savedDevices.isNotEmpty()) {
                Text(
                    "已保存设备",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.savedDevices.forEach { saved ->
                        val scanned = state.devices.firstOrNull { it.address == saved.address }
                        ConnectionDeviceRow(
                            device = scanned ?: ScanDevice(
                                address = saved.address,
                                name = saved.name,
                                rssi = Int.MIN_VALUE,
                                looksLikeJbd = true
                            ),
                            state = state,
                            remembered = true,
                            connect = { connect(saved.address) },
                            disconnect = disconnect
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("附近设备", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                val savedAddresses = state.savedDevices.map { it.address }.toSet()
                val nearbyCount = state.devices.count { it.address !in savedAddresses }
                Text("$nearbyCount 个", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            val savedAddresses = state.savedDevices.map { it.address }.toSet()
            val nearbyDevices = state.devices.filter { it.address !in savedAddresses }
            if (nearbyDevices.isEmpty()) {
                EmptyDevices(state.phase == ConnectionPhase.Scanning)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(nearbyDevices, key = { it.address }) { device ->
                        ConnectionDeviceRow(
                            device = device,
                            state = state,
                            remembered = false,
                            connect = { connect(device.address) },
                            disconnect = disconnect
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(text: String, action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(vertical = 5.dp))
    }
}

@Composable
private fun InfoCard(text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Text(text, color = color, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun EmptyDevices(scanning: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 58.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (scanning) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text(if (scanning) "正在寻找蓝牙设备…" else "尚未扫描到设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionDeviceRow(
    device: ScanDevice,
    state: BmsUiState,
    remembered: Boolean,
    connect: () -> Unit,
    disconnect: () -> Unit
) {
    val isCurrent = state.connectedAddress == device.address
    val status = when {
        isCurrent && state.phase == ConnectionPhase.Ready -> "已连接"
        isCurrent && state.phase == ConnectionPhase.Connecting -> "连接中"
        isCurrent && state.phase == ConnectionPhase.Reconnecting -> "等待重连"
        isCurrent && state.phase == ConnectionPhase.Discovering -> "正在识别"
        isCurrent && state.phase == ConnectionPhase.Disconnecting -> "断开中"
        else -> "未连接"
    }
    val statusColor = when (status) {
        "已连接" -> MaterialTheme.colorScheme.primary
        "连接中", "正在识别", "等待重连" -> MaterialTheme.colorScheme.secondary
        "断开中" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val anotherDeviceBusy = state.connectedAddress != null && !isCurrent &&
        state.phase != ConnectionPhase.Idle && state.phase != ConnectionPhase.Error
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (remembered) 20.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(
                    if (device.looksLikeJbd) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("◉", color = if (device.looksLikeJbd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(status, color = statusColor, fontSize = 11.sp)
                    if (remembered) {
                        Spacer(Modifier.width(7.dp))
                        Text("已保存", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (device.rssi != Int.MIN_VALUE) {
                    Text("${device.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                when {
                    isCurrent && (state.phase == ConnectionPhase.Ready ||
                        state.phase == ConnectionPhase.Connecting || state.phase == ConnectionPhase.Discovering) ->
                        OutlinedButton(onClick = disconnect) { Text(if (state.phase == ConnectionPhase.Ready) "断开" else "取消") }
                    isCurrent && state.phase == ConnectionPhase.Reconnecting ->
                        OutlinedButton(onClick = disconnect) { Text("取消重连") }
                    isCurrent && state.phase == ConnectionPhase.Disconnecting ->
                        OutlinedButton(onClick = {}, enabled = false) { Text("断开中") }
                    else -> Button(onClick = connect, enabled = !anotherDeviceBusy) { Text("连接") }
                }
            }
        }
    }
}

@Composable
private fun ProgressPanel(state: BmsUiState, disconnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(22.dp))
        Text(
            when (state.phase) {
                ConnectionPhase.Connecting -> "正在连接 ${state.connectedName.orEmpty()}"
                ConnectionPhase.Reconnecting -> "等待自动重新连接"
                ConnectionPhase.Discovering -> "正在识别通信服务"
                else -> "正在断开连接"
            },
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(state.connectedAddress.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        if (state.phase != ConnectionPhase.Disconnecting) {
            Spacer(Modifier.height(22.dp))
            OutlinedButton(onClick = disconnect) { Text("取消") }
        }
    }
}

@Composable
private fun Dashboard(
    state: BmsUiState,
    onShowDevices: () -> Unit,
    onClearLogs: () -> Unit,
    onSubmitPassword: (String) -> Boolean
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        DeviceSummary(state, onShowDevices, onSubmitPassword)
        TabSelector(tab) { tab = it }
        when (tab) {
            0 -> Overview(state)
            1 -> CellsPanel(state.cells, state.basicInfo?.balancingMask ?: 0)
            else -> LogsPanel(state.logs, onClearLogs)
        }
    }
}

@Composable
private fun DeviceSummary(state: BmsUiState, onShowDevices: () -> Unit, onSubmitPassword: (String) -> Boolean) {
    var showSettings by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(state.authenticationRequired) {
        if (state.authenticationRequired) showSettings = true
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("电池详情", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        OutlinedButton(onClick = onShowDevices) { Text("设备") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { showSettings = true }) { Text("设置") }
    }
    if (showSettings) {
        DeviceSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onSubmitPassword = onSubmitPassword
        )
    }
}

@Composable
private fun DeviceSettingsDialog(
    state: BmsUiState,
    onDismiss: () -> Unit,
    onSubmitPassword: (String) -> Boolean
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
private fun TabSelector(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("概览", "单体", "通信日志")
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(index) },
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Overview(state: BmsUiState) {
    val info = state.basicInfo
    if (info == null) {
        EmptyReading("正在等待 BMS 基本数据…")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SocHero(info) }
        item {
            val currentLabel = when {
                info.currentA < -0.05 -> "当前放电电流"
                info.currentA > 0.05 -> "当前充电电流"
                else -> "当前电流"
            }
            MetricRow(
                Metric("电池当前总电压", format(info.totalVoltageV, "V"), "实时总压"),
                Metric(
                    currentLabel,
                    format(abs(info.currentA), "A"),
                    when {
                        info.currentA < -0.05 -> "正在放电"
                        info.currentA > 0.05 -> "正在充电"
                        else -> "无充放电"
                    }
                )
            )
        }
        item {
            MetricRow(
                Metric("当前剩余容量", format(info.remainingCapacityAh, "Ah"), "剩余安时"),
                Metric("电池总容量", format(info.nominalCapacityAh, "Ah"), "总安时（Ah）")
            )
        }
        item {
            MetricRow(
                Metric("循环次数", "${info.cycleCount}", "BMS 记录"),
                Metric("健康度", info.estimatedSohPercent?.let { format(it, "%") } ?: "--", "估算 SOH")
            )
        }
        item {
            MetricRow(
                Metric("本次充入", format(state.sessionChargeAh, "Ah", 3), "App 统计"),
                Metric("本次放出", format(state.sessionDischargeAh, "Ah", 3), "App 统计")
            )
        }
        item { TemperatureCard(info) }
        item { MosAndProtectionCard(info) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private data class Metric(val label: String, val value: String, val note: String)

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(left, Modifier.weight(1f))
        MetricCard(right, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(metric.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Text(metric.value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(metric.note, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun SocHero(info: BmsBasicInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { info.stateOfChargePercent / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${info.stateOfChargePercent}%", fontWeight = FontWeight.Black, fontSize = 27.sp)
                    Text("SOC", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text("电池状态", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(
                    when {
                        info.currentA > 0.05 -> "正在充电"
                        info.currentA < -0.05 -> "正在放电"
                        else -> "静置"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Spacer(Modifier.height(5.dp))
                Text("${info.cellCount} 串电池", fontSize = 12.sp)
                info.productionDate?.let { Text("生产日期 $it", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun TemperatureCard(info: BmsBasicInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("温度探头", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (info.temperaturesC.isEmpty()) {
                Text("未返回温度数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                info.temperaturesC.forEachIndexed { index, temperature ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text("温度 ${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(format(temperature, "℃"), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MosAndProtectionCard(info: BmsBasicInfo) {
    val protection = protectionText(info.protectionMask)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("运行状态", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Flag("充电 MOS", info.chargeMosEnabled)
                Flag("放电 MOS", info.dischargeMosEnabled)
                Flag("保护正常", info.protectionMask == 0)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (protection.isEmpty()) "当前无保护告警" else protection.joinToString("、"),
                color = if (protection.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun Flag(label: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        shape = CircleShape
    ) {
        Text(
            "${if (active) "●" else "○"} $label",
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CellsPanel(cells: CellSummary?, balancingMask: Long) {
    if (cells == null) {
        EmptyReading("正在等待单体电压…")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallSummary("最低", cells.minimumMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
            SmallSummary("最高", cells.maximumMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
            SmallSummary("压差", cells.deltaMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(cells.millivolts) { index, mv ->
                CellCard(index, mv, balancingMask and (1L shl index) != 0L, cells.minimumMv, cells.maximumMv)
            }
        }
    }
}

@Composable
private fun SmallSummary(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CellCard(index: Int, mv: Int, balancing: Boolean, min: Int?, max: Int?) {
    val accent = when (mv) {
        min -> MaterialTheme.colorScheme.secondary
        max -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("单体 ${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("%.3f V".format(Locale.US, mv / 1000.0), color = accent, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            if (balancing) Text("均衡", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun LogsPanel(logs: List<RawLogEntry>, onClear: () -> Unit) {
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

@Composable
private fun EmptyReading(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun protectionText(mask: Int): List<String> {
    val names = listOf(
        "单体过压", "单体欠压", "总压过高", "总压过低",
        "充电高温", "充电低温", "放电高温", "放电低温",
        "充电过流", "放电过流", "短路", "前端芯片异常",
        "软件关闭 MOS", "充电 MOS 异常", "放电 MOS 异常"
    )
    return names.mapIndexedNotNull { index, name -> name.takeIf { mask and (1 shl index) != 0 } }
}

private fun format(value: Double, unit: String, decimals: Int = 2): String =
    "%.${decimals}f %s".format(Locale.US, value, unit)

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun time(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(timeFormatter)
