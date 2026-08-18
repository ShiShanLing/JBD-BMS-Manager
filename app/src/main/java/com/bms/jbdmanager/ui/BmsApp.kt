package com.bms.jbdmanager.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.bms.jbdmanager.R
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
        when (state.phase) {
            ConnectionPhase.Ready -> showDashboard = true
            ConnectionPhase.Idle, ConnectionPhase.Error -> showDashboard = false
            else -> Unit
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
            if (showDashboard && state.phase == ConnectionPhase.Ready) {
                Dashboard(
                    state = state,
                    onShowDevices = { showDashboard = false },
                    onClearLogs = viewModel::clearLogs,
                    onSubmitPassword = viewModel::submitBluetoothPassword
                )
            } else {
                AppHeader(
                    state = state,
                    refreshNearby = {
                        when {
                            !state.permissionsGranted -> requestPermissions()
                            !state.bluetoothEnabled -> requestEnableBluetooth()
                            state.isScanning -> viewModel.stopScan()
                            else -> viewModel.startScan()
                        }
                    }
                )
                ScanPanel(
                    state = state,
                    connect = viewModel::connect,
                    disconnect = viewModel::disconnect,
                    showDashboard = { showDashboard = true }
                )
            }
        }
    }
}

@Composable
private fun AppHeader(state: BmsUiState, refreshNearby: () -> Unit) {
    val refreshRotation by rememberInfiniteTransition(label = "refreshRotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refreshRotationAngle"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_legacy),
            contentDescription = "JBD BMS 应用图标",
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("JBD BMS", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("安全只读监控", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        IconButton(onClick = refreshNearby, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = if (state.isScanning) "停止扫描" else "刷新附近设备",
                tint = if (state.isScanning) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(38.dp)
                    .rotate(if (state.isScanning) refreshRotation else 0f)
            )
        }
        Spacer(Modifier.width(6.dp))
        StatusBadge(state)
    }
}

@Composable
private fun StatusBadge(state: BmsUiState) {
    val (text, color) = if (state.isScanning) {
        "扫描中" to MaterialTheme.colorScheme.secondary
    } else when (state.phase) {
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
        Text(text, color = color, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 10.sp)
    }
}

@Composable
private fun ScanPanel(
    state: BmsUiState,
    connect: (String) -> Unit,
    disconnect: () -> Unit,
    showDashboard: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("连接电池", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            when {
                !state.bluetoothSupported -> InfoCard("此设备不支持低功耗蓝牙（BLE）", MaterialTheme.colorScheme.error)
                !state.permissionsGranted -> InfoCard("点击顶部刷新图标，允许附近设备权限", MaterialTheme.colorScheme.secondary)
                !state.bluetoothEnabled -> InfoCard("点击顶部刷新图标开启蓝牙", MaterialTheme.colorScheme.secondary)
                state.phase == ConnectionPhase.Ready && state.authenticationRequired ->
                    PrimaryAction("输入蓝牙读取密码", showDashboard)
                state.phase == ConnectionPhase.Ready -> Unit
                state.isScanning -> Unit
                state.phase == ConnectionPhase.Connecting || state.phase == ConnectionPhase.Discovering ->
                    InfoCard("正在连接设备，请在下方设备卡片查看进度", MaterialTheme.colorScheme.secondary)
                state.phase == ConnectionPhase.Reconnecting ->
                    InfoCard("连接已中断，${state.reconnectInSeconds ?: 0} 秒后自动重连", MaterialTheme.colorScheme.secondary)
                state.phase == ConnectionPhase.Disconnecting ->
                    InfoCard("正在断开设备…", MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Unit
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
                        val displayDevice = scanned?.let {
                            if (it.name == "未命名设备" && saved.name != "未命名设备") it.copy(name = saved.name) else it
                        }
                        ConnectionDeviceRow(
                            device = displayDevice ?: ScanDevice(
                                address = saved.address,
                                name = saved.name,
                                rssi = Int.MIN_VALUE,
                                looksLikeJbd = true
                            ),
                            state = state,
                            remembered = true,
                            lastSocPercent = saved.lastSocPercent,
                            openDetails = showDashboard,
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
                EmptyDevices(state.isScanning)
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
                            openDetails = showDashboard,
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
    lastSocPercent: Int? = null,
    openDetails: () -> Unit,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (remembered) 20.dp else 0.dp)
            .clickable(
                enabled = isCurrent && state.phase == ConnectionPhase.Ready,
                onClick = openDetails
            ),
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
                Text(
                    buildString {
                        append(device.name)
                        if (remembered && lastSocPercent != null) append(" · $lastSocPercent%")
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
        ) { Text("设备", fontSize = 11.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = { showSettings = true },
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) { Text("设置", fontSize = 11.sp) }
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
    val labels = listOf("概览", "通信日志")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(index) },
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 7.dp), fontSize = 11.sp)
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
            val cells = state.cells
            val nearFull = isNearFull(info, cells)
            val deltaColor = deltaAlertColor(cells?.deltaMv, nearFull)
            val voltageSummary = if (cells?.millivolts.isNullOrEmpty()) {
                "--"
            } else {
                val averageVoltage = cells!!.millivolts.average() / 1000.0
                "${cells.deltaMv ?: 0} mV/${"%.3f".format(Locale.US, averageVoltage)} V"
            }
            MetricRow(
                Metric(
                    "剩余容量/总容量",
                    "${compactNumber(info.remainingCapacityAh)}/${compactNumber(info.nominalCapacityAh)} Ah",
                    "剩余/总安时"
                ),
                Metric(
                    "压差/平均电压",
                    voltageSummary,
                    "单体压差/平均值",
                    deltaColor
                )
            )
        }
        item {
            val sohColor = healthAlertColor(info.estimatedSohPercent)
            MetricRow(
                Metric("循环次数", "${info.cycleCount}", "BMS 记录"),
                Metric(
                    "健康度",
                    info.estimatedSohPercent?.let { format(it, "%") } ?: "--",
                    "估算 SOH",
                    sohColor
                )
            )
        }
        item { TemperatureCard(info) }
        item {
            CellsOverviewSection(
                state.cells,
                info.balancingMask,
                isNearFull(info, state.cells)
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

private data class Metric(
    val label: String,
    val value: String,
    val note: String,
    val valueColor: Color? = null
)

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricCard(left, Modifier.weight(1f).fillMaxHeight())
        MetricCard(right, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 6.dp)) {
            Text(
                metric.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 13.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                metric.value,
                color = metric.valueColor ?: MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 18.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                metric.note,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SocHero(info: BmsBasicInfo) {
    val batteryState = when {
        info.currentA > 0.05 -> "正在充电"
        info.currentA < -0.05 -> "正在放电"
        else -> "静置"
    }
    val batteryStateColor = when {
        info.currentA > 0.05 -> MaterialTheme.colorScheme.primary
        info.currentA < -0.05 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { info.stateOfChargePercent / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${info.stateOfChargePercent}%", fontWeight = FontWeight.Black, fontSize = 21.sp)
                    Text("SOC", fontSize = 12.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("电池状态", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(
                    batteryState,
                    color = batteryStateColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                )
                Spacer(Modifier.height(2.dp))
                Text("${info.cellCount} 串电池", fontSize = 12.sp)
                info.productionDate?.let { Text("生产日期 $it", fontSize = 11.sp) }
            }
            RuntimeStatusColumn(info)
        }
    }
}

@Composable
private fun RuntimeStatusColumn(info: BmsBasicInfo) {
    val protection = protectionText(info.protectionMask)
    val balancing = info.balancingMask != 0L
    Column(
        modifier = Modifier.width(82.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("运行状态", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 13.sp)
        CompactStatus("充电 MOS", info.chargeMosEnabled)
        CompactStatus("放电 MOS", info.dischargeMosEnabled)
        CompactStatus("电池均衡", balancing)
        CompactStatus("电池保护", protection.isNotEmpty(), dangerWhenActive = true)
    }
}

@Composable
private fun CompactStatus(
    label: String,
    active: Boolean,
    dangerWhenActive: Boolean = false
) {
    val activeColor = if (dangerWhenActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .then(
                    if (active) {
                        Modifier.background(activeColor, CircleShape)
                    } else {
                        Modifier.border(1.dp, inactiveColor, CircleShape)
                    }
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun TemperatureCard(info: BmsBasicInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(13.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("温度探头", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
            if (info.temperaturesC.isEmpty()) {
                Text(
                    "未返回温度数据",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
            } else {
                info.temperaturesC.forEachIndexed { index, temperature ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("T${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                            Text(format(temperature, "℃", 1), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellsOverviewSection(cells: CellSummary?, balancingMask: Long, nearFull: Boolean) {
    if (cells == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp)
        ) {
            Text(
                "正在等待单体电压…",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text("单体电压", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallSummary("最低", cells.minimumMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
            SmallSummary("最高", cells.maximumMv?.let { "$it mV" } ?: "--", Modifier.weight(1f))
            SmallSummary(
                "压差",
                cells.deltaMv?.let { "$it mV" } ?: "--",
                Modifier.weight(1f),
                deltaAlertColor(cells.deltaMv, nearFull)
            )
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.millivolts.withIndex().chunked(5).forEach { rowCells ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowCells.forEach { (index, mv) ->
                        CellCard(
                            index = index,
                            mv = mv,
                            balancing = balancingMask and (1L shl index) != 0L,
                            min = cells.minimumMv,
                            max = cells.maximumMv,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(5 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SmallSummary(label: String, value: String, modifier: Modifier, valueColor: Color? = null) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private val AlertYellow = Color(0xFFFFC247)
private val AlertOrange = Color(0xFFFF8A3D)

@Composable
private fun deltaAlertColor(deltaMv: Int?, nearFull: Boolean): Color? {
    if (deltaMv == null) return null
    return if (nearFull) {
        when {
            deltaMv < 150 -> null
            deltaMv < 200 -> AlertYellow
            deltaMv < 300 -> AlertOrange
            else -> MaterialTheme.colorScheme.error
        }
    } else {
        when {
            deltaMv < 30 -> null
            deltaMv < 80 -> AlertYellow
            deltaMv < 150 -> AlertOrange
            else -> MaterialTheme.colorScheme.error
        }
    }
}

private fun isNearFull(info: BmsBasicInfo, cells: CellSummary?): Boolean =
    info.stateOfChargePercent >= 95 || (cells?.maximumMv ?: 0) >= 3400

@Composable
private fun healthAlertColor(sohPercent: Double?): Color? = when {
    sohPercent == null || sohPercent >= 90.0 -> null
    sohPercent < 75.0 -> MaterialTheme.colorScheme.error
    sohPercent < 80.0 -> AlertOrange
    else -> AlertYellow
}

@Composable
private fun CellCard(index: Int, mv: Int, balancing: Boolean, min: Int?, max: Int?, modifier: Modifier = Modifier) {
    val accent = when (mv) {
        min -> MaterialTheme.colorScheme.secondary
        max -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(10.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 6.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("单体 ${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, lineHeight = 10.sp)
                Text(
                    "%.3f V".format(Locale.US, mv / 1000.0),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    maxLines = 1
                )
            }
            if (balancing) {
                Text(
                    "均衡",
                    modifier = Modifier.align(Alignment.TopStart),
                    color = Color(0xFFD0A8FF),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
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

private fun compactNumber(value: Double, decimals: Int = 2): String =
    "%.${decimals}f".format(Locale.US, value).trimEnd('0').trimEnd('.')

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun time(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(timeFormatter)
