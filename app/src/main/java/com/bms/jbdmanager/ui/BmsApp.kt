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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.bms.jbdmanager.model.BrakeTestPhase
import com.bms.jbdmanager.model.BrakeTestState
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.RawLogEntry
import com.bms.jbdmanager.model.SpeedRangeStats
import com.bms.jbdmanager.model.ScanDevice
import com.bms.jbdmanager.model.TripState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.content.Intent

@Composable
fun BmsApp(
    viewModel: BmsViewModel,
    exitApp: () -> Unit,
    requestPermissions: () -> Unit,
    requestEnableBluetooth: () -> Unit,
    requestLocationPermission: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDashboard by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var previewMode by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var locationPermissionRequestedForConnection by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var previewState by remember { androidx.compose.runtime.mutableStateOf(demoBmsState()) }
    var showExitConfirmation by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.phase, previewMode) {
        if (!previewMode) {
            when (state.phase) {
                ConnectionPhase.Ready -> showDashboard = true
                ConnectionPhase.Idle, ConnectionPhase.Error -> showDashboard = false
                else -> Unit
            }
        }
    }

    LaunchedEffect(state.phase, state.basicInfo, state.locationPermissionGranted, previewMode) {
        if (
            !previewMode && state.phase == ConnectionPhase.Ready && state.basicInfo != null &&
            !state.locationPermissionGranted && !locationPermissionRequestedForConnection
        ) {
            locationPermissionRequestedForConnection = true
            requestLocationPermission()
        }
        if (state.phase == ConnectionPhase.Idle) locationPermissionRequestedForConnection = false
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
            if (showDashboard && (previewMode || state.phase == ConnectionPhase.Ready)) {
                Dashboard(
                    state = if (previewMode) previewState else state,
                    onShowDevices = {
                        previewMode = false
                        showDashboard = false
                    },
                    onClearLogs = if (previewMode) ({}) else viewModel::clearLogs,
                    onSubmitPassword = viewModel::submitBluetoothPassword,
                    onRequestLocationPermission = requestLocationPermission,
                    onRequestExit = { showExitConfirmation = true },
                    onArmBrakeTest = if (previewMode) ({}) else viewModel::armBrakeTest,
                    onCancelBrakeTest = if (previewMode) ({}) else viewModel::cancelBrakeTest,
                    onClearSpeedRangeStats = if (previewMode) ({}) else viewModel::clearSpeedRangeStats,
                    onCyclePreviewBatteryState = if (previewMode) {
                        {
                            previewState = previewState.copy(
                                basicInfo = previewState.basicInfo?.let { info ->
                                    val nextCurrent = when {
                                        info.currentA < -0.05 -> 18.6
                                        info.currentA > 0.05 -> 0.0
                                        else -> -18.6
                                    }
                                    info.copy(currentA = nextCurrent, updatedAtMillis = System.currentTimeMillis())
                                }
                            )
                        }
                    } else null
                )
            } else {
                val refreshNearby: () -> Unit = {
                    when {
                        !state.permissionsGranted -> requestPermissions()
                        !state.bluetoothEnabled -> requestEnableBluetooth()
                        state.isScanning -> viewModel.stopScan()
                        else -> viewModel.startScan()
                    }
                }
                AppHeader(state = state, onRequestExit = { showExitConfirmation = true })
                ScanPanel(
                    state = state,
                    connect = viewModel::connect,
                    disconnect = viewModel::disconnect,
                    refreshNearby = refreshNearby,
                    showDashboard = { showDashboard = true },
                    showPreview = {
                        previewMode = true
                        showDashboard = true
                    }
                )
            }
        }
    }
    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("退出并停止全部？") },
            text = { Text("将停止 GPS、结束行程、关闭常驻通知、取消自动重连并断开蓝牙。") },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        exitApp()
                    }
                ) { Text("退出全部", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun AppHeader(state: BmsUiState, onRequestExit: () -> Unit) {
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
        StatusBadge(state)
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRequestExit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("退出", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
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
    refreshNearby: () -> Unit,
    showDashboard: () -> Unit,
    showPreview: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("连接电池", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            when {
                !state.bluetoothSupported -> InfoCard("此设备不支持低功耗蓝牙（BLE）", MaterialTheme.colorScheme.error)
                !state.permissionsGranted -> InfoCard("点击“附近设备”右侧按钮，允许附近设备权限", MaterialTheme.colorScheme.secondary)
                !state.bluetoothEnabled -> InfoCard("点击“附近设备”右侧按钮开启蓝牙", MaterialTheme.colorScheme.secondary)
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
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = showPreview, modifier = Modifier.fillMaxWidth()) {
                Text("预览详情（测试数据）")
            }
        }

        val savedAddresses = state.savedDevices.map { it.address }.toSet()
        val nearbyDevices = state.devices.filter { it.address !in savedAddresses }

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
        }

        NearbyDevicesHeader(
            nearbyCount = nearbyDevices.size,
            isScanning = state.isScanning,
            refreshNearby = refreshNearby
        )

        if (state.permissionsGranted && state.bluetoothEnabled) {
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
private fun NearbyDevicesHeader(
    nearbyCount: Int,
    isScanning: Boolean,
    refreshNearby: () -> Unit
) {
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
        modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("附近设备", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text("$nearbyCount 个", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        IconButton(onClick = refreshNearby, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = if (isScanning) "停止扫描" else "开始扫描设备",
                tint = if (isScanning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(38.dp)
                    .rotate(if (isScanning) refreshRotation else 0f)
            )
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
    onSubmitPassword: (String) -> Boolean,
    onRequestLocationPermission: () -> Unit,
    onRequestExit: () -> Unit,
    onArmBrakeTest: (Int) -> Unit,
    onCancelBrakeTest: () -> Unit,
    onClearSpeedRangeStats: () -> Unit,
    onCyclePreviewBatteryState: (() -> Unit)?
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        DeviceSummary(state, onShowDevices, onSubmitPassword, onRequestExit)
        TabSelector(tab) { tab = it }
        when (tab) {
            0 -> Overview(state, onRequestLocationPermission, onCyclePreviewBatteryState)
            1 -> RangeTestPage(state, onRequestLocationPermission, onClearSpeedRangeStats)
            2 -> BrakeTestPage(state, onArmBrakeTest, onCancelBrakeTest)
            else -> LogsPanel(state.logs, onClearLogs)
        }
    }
}

@Composable
private fun DeviceSummary(
    state: BmsUiState,
    onShowDevices: () -> Unit,
    onSubmitPassword: (String) -> Boolean,
    onRequestExit: () -> Unit
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
        ) { Text("设备", fontSize = 11.sp) }
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
    val labels = listOf("概览", "续航测试", "刹车测试", "通信日志")
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
private fun Overview(
    state: BmsUiState,
    onRequestLocationPermission: () -> Unit,
    onCyclePreviewBatteryState: (() -> Unit)?
) {
    val info = state.basicInfo
    if (info == null) {
        EmptyReading("正在等待 BMS 基本数据…")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { SocHero(info, onCyclePreviewBatteryState) }
        item { GpsSpeedPanel(state.gpsSpeed) }
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
                Metric("电池当前总电压", format(info.totalVoltageV, "V"), "实时总压"),
                Metric(
                    "循环次数/健康度",
                    "${info.cycleCount} 次 / ${info.estimatedSohPercent?.let { format(it, "%") } ?: "--"}",
                    "BMS 记录/估算 SOH",
                    sohColor
                )
            )
        }
        item { TripCard(state.trip, state.locationPermissionGranted, onRequestLocationPermission) }
        item { TemperatureCard(info) }
        item {
            CellsOverviewSection(
                state.cells,
                info.cellCount,
                info.balancingMask,
                isNearFull(info, state.cells)
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun TripCard(
    trip: TripState,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GPS 本次行程", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (trip.isTracking) "记录中 · ${trip.estimateConfidence}" else trip.estimateConfidence,
                    color = if (trip.isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripPrimaryMetric(
                    label = "已行驶",
                    value = if (trip.startedAtMillis == null) "--" else "${compactNumber(trip.distanceKm)} km",
                    modifier = Modifier.weight(1f)
                )
                TripPrimaryMetric(
                    label = "预计剩余续航",
                    value = trip.estimatedRemainingKm?.let { "${compactNumber(it)} km" } ?: "采集中",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(7.dp))
            TripDetailRow(
                "SOC",
                trip.startSocPercent?.let { start ->
                    buildAnnotatedString {
                        append("$start%")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" → ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append("${trip.currentSocPercent ?: start}%")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append("（下降 ${trip.socDropPercent ?: 0}%）")
                        }
                    }
                } ?: AnnotatedString("等待 BMS 数据")
            )
            TripDetailRow(
                "剩余容量",
                trip.startRemainingAh?.let { start ->
                    buildAnnotatedString {
                        append(compactNumber(start))
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" → ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append("${compactNumber(trip.currentRemainingAh ?: start)} Ah")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append("（消耗 ${compactNumber(trip.consumedAh)} Ah）")
                        }
                    }
                } ?: AnnotatedString("等待 BMS 数据")
            )
            TripDetailRow(
                "平均电耗",
                buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = if (trip.ahPer100Km == null) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(trip.ahPer100Km?.let { "${compactNumber(it)} Ah/100km" } ?: "采集中")
                    }
                    trip.whPerKm?.let {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" · ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append("${compactNumber(it)} Wh/km")
                        }
                    }
                }
            )
            if (!locationPermissionGranted) {
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("允许精确位置并开始行程", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun GpsSpeedPanel(speed: GpsSpeedState) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                "GPS 时速 · km/h",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                GpsSpeedMetric("当前时速", speed.currentKmh, Modifier.weight(1f))
                GpsSpeedMetric("近5秒平均", speed.average5SecondsKmh, Modifier.weight(1f))
                GpsSpeedMetric("最高时速", speed.maximumKmh, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GpsSpeedMetric(label: String, value: Double, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        Text(
            compactNumber(value, 1),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 22.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun TripPrimaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TripDetailRow(label: String, value: AnnotatedString) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(68.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 2
        )
    }
}

@Composable
private fun BrakeTestPage(
    state: BmsUiState,
    onArmBrakeTest: (Int) -> Unit,
    onCancelBrakeTest: () -> Unit
) {
    val test = state.trip.brakeTest
    var selectedSpeed by rememberSaveable { mutableIntStateOf(test.targetSpeedKmh) }
    val presets = listOf(25, 30, 35, 40, 45, 50, 55, 60, 65)
    val phaseText = when (test.phase) {
        BrakeTestPhase.Idle -> "未开始"
        BrakeTestPhase.Armed -> "等待达到目标速度"
        BrakeTestPhase.Ready -> "已就绪"
        BrakeTestPhase.Braking -> "制动中"
        BrakeTestPhase.Complete -> "测试完成"
        BrakeTestPhase.Failed -> "测试无效"
    }
    val phaseColor = when (test.phase) {
        BrakeTestPhase.Ready, BrakeTestPhase.Complete -> MaterialTheme.colorScheme.primary
        BrakeTestPhase.Armed, BrakeTestPhase.Braking -> MaterialTheme.colorScheme.secondary
        BrakeTestPhase.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("刹车距离测试", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(phaseText, color = phaseColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            compactNumber(test.currentSpeedKmh, 1),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            fontSize = 30.sp,
                            lineHeight = 32.sp
                        )
                        Text(" km/h", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text(test.message, color = phaseColor, fontSize = 12.sp, lineHeight = 15.sp)
                }
            }
        }
        item {
            Text("选择起始速度", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                presets.forEach { speed ->
                    val selected = selectedSpeed == speed
                    Surface(
                        modifier = Modifier.weight(1f).clickable {
                            if (!test.isRunning) selectedSpeed = speed
                        },
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "$speed",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 7.dp)
                        )
                    }
                }
            }
        }
        item {
            if (test.isRunning) {
                OutlinedButton(onClick = onCancelBrakeTest, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("取消本次测试")
                }
            } else {
                Button(
                    onClick = { onArmBrakeTest(selectedSpeed) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("开始测试 · $selectedSpeed km/h", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            MetricRow(
                Metric("刹车距离", if (test.phase == BrakeTestPhase.Complete) format(test.brakingDistanceMeters, "m") else "--", "速度积分估算"),
                Metric("刹车时间", if (test.phase == BrakeTestPhase.Complete) format(test.brakingDurationSeconds, "s") else "--", "目标速度至停止")
            )
        }
        item {
            MetricRow(
                Metric("平均减速度", test.averageDecelerationMps2?.let { format(it, "m/s²") } ?: "--", "制动强度"),
                Metric("GPS 采样", "${compactNumber(test.sampleRateHz, 1)} Hz", "可信度：${test.confidence}")
            )
        }
        item {
            Text(
                "请只在封闭、空旷、无行人车辆的安全路段测试，并将手机牢固固定。手机 GPS 实际采样频率由硬件决定，结果仅用于个人对比。",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RangeTestPage(
    state: BmsUiState,
    onRequestLocationPermission: () -> Unit,
    onClearSpeedRangeStats: () -> Unit
) {
    var selectedTarget by rememberSaveable { mutableIntStateOf(40) }
    var showClearConfirmation by remember { androidx.compose.runtime.mutableStateOf(false) }
    val selectedStats = state.trip.speedRangeStats.firstOrNull { it.targetSpeedKmh == selectedTarget }
        ?: state.trip.speedRangeStats.first()
    val currentStats = state.trip.speedRangeStats.firstOrNull { it.accepts(state.trip.currentSpeedKmh) }
    val sampleStatus = when {
        !state.trip.isTracking -> "连接 BMS 后自动开始分档统计"
        currentStats != null -> "当前 ${compactNumber(state.trip.currentSpeedKmh)}km/h，自动计入 ${currentStats.targetSpeedKmh}km/h 档"
        else -> "当前速度不在预设档位，综合行程继续记录"
    }
    val sampleColor = if (state.trip.isTracking && currentStats != null) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val estimatedRange = selectedStats.estimatedRemainingKm(state.trip.currentRemainingAh)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)),
                shape = RoundedCornerShape(15.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动速度续航", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            if (state.trip.isTracking) "自动统计中" else "等待连接",
                            color = if (state.trip.isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "行驶数据会自动归入最接近的速度档位，并长期累计保存。骑行样本越多，续航估算越稳定。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("点击查看不同速度的续航结果", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(25, 30, 35, 40, 45, 50, 55, 60).forEach { speed ->
                            val selected = selectedTarget == speed
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedTarget = speed },
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Text(
                                    "$speed",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 9.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = sampleColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).background(sampleColor, CircleShape))
                            Spacer(Modifier.width(7.dp))
                            Text(sampleStatus, color = sampleColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {
            MetricRow(
                Metric("${selectedStats.targetSpeedKmh}档有效里程", "${compactNumber(selectedStats.effectiveDistanceKm)} km", "仅该速度区间"),
                Metric(
                    "该速度预计续航",
                    estimatedRange?.let { "${compactNumber(it)} km" } ?: "采集中",
                    selectedStats.confidence,
                    if (estimatedRange != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            )
        }
        item {
            MetricRow(
                Metric(
                    "当前速度",
                    "${compactNumber(state.trip.currentSpeedKmh)} km/h",
                    currentStats?.let { "正在计入 ${it.targetSpeedKmh} 档" } ?: "未进入速度档",
                    if (currentStats != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                Metric(
                    "查看速度区间",
                    "${compactNumber(selectedStats.minimumSpeedKmh)}–${compactNumber(selectedStats.maximumSpeedKmh)}",
                    "${selectedStats.targetSpeedKmh} km/h 档"
                )
            )
        }
        item {
            MetricRow(
                Metric("该档消耗容量", "${compactNumber(selectedStats.consumedAh)} Ah", "该速度内积分"),
                Metric("该档消耗电量", "${compactNumber(selectedStats.consumedWh)} Wh", "电压 × 电流 × 时间")
            )
        }
        item {
            MetricRow(
                Metric(
                    "平均电耗",
                    selectedStats.ahPer100Km?.let { "${compactNumber(it)} Ah/100km" } ?: "采集中",
                    "容量电耗"
                ),
                Metric(
                    "能耗强度",
                    selectedStats.whPerKm?.let { "${compactNumber(it)} Wh/km" } ?: "采集中",
                    "长途比较值"
                )
            )
        }
        item {
            MetricRow(
                Metric(
                    "该档平均速度",
                    selectedStats.averageSpeedKmh?.let { "${compactNumber(it)} km/h" } ?: "采集中",
                    "有效样本平均值"
                ),
                Metric(
                    "当前剩余容量",
                    state.trip.currentRemainingAh?.let { "${compactNumber(it)} Ah" } ?: "--",
                    "用于计算剩余续航"
                )
            )
        }
        item {
            if (!state.locationPermissionGranted) {
                OutlinedButton(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("允许精确位置权限")
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { showClearConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空长期累计样本", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Text(
                if (state.trip.isTracking) {
                    "所有速度档位会在后台同步长期积累。切换按钮只查看对应档位结果；单档有效里程达到 3km 后提供初步估算。"
                } else {
                    "重新连接 BMS 后会在现有累计样本上继续统计，不会自动清零。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
            )
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("清空续航样本？") },
            text = { Text("所有速度档位长期累计的里程、耗电量和耗时都会清零，且无法恢复。") },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearSpeedRangeStats()
                    }
                ) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            }
        )
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
private fun SocHero(info: BmsBasicInfo, onCyclePreviewBatteryState: (() -> Unit)?) {
    val batteryState = when {
        info.currentA > 0.05 -> "正在充电"
        info.currentA < -0.05 -> "正在放电"
        else -> "静置"
    }
    val currentText = if (kotlin.math.abs(info.currentA) <= 0.05) {
        "0.00A"
    } else {
        format(info.currentA, "A").replace(" ", "")
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
                Column(
                    modifier = if (onCyclePreviewBatteryState != null) {
                        Modifier.clickable(onClick = onCyclePreviewBatteryState)
                    } else Modifier
                ) {
                    Text(
                        batteryState,
                        color = batteryStateColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        maxLines = 1
                    )
                    Text(
                        currentText,
                        color = batteryStateColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        lineHeight = 14.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(2.dp))
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
private fun CellsOverviewSection(cells: CellSummary?, cellCount: Int, balancingMask: Long, nearFull: Boolean) {
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
        Text("单体电压 · ${cellCount}串", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
        Column(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

private fun demoBmsState(): BmsUiState {
    val now = System.currentTimeMillis()
    return BmsUiState(
        permissionsGranted = true,
        phase = ConnectionPhase.Ready,
        connectedAddress = "A5:C2:39:53:FB:40",
        connectedName = "DB24SA03L24S60ABU（测试数据）",
        modelName = "DB24SA03L24S60ABU",
        protocolProfile = "标准 JBD BLE（FF00/FF01/FF02）",
        detectedProtocol = "JBD DD/77（V12扩展）",
        bleChannelDetails = "FF00 服务 · FF01 通知 · FF02 写入",
        chipType = "凹凸方案",
        basicInfo = BmsBasicInfo(
            totalVoltageV = 55.70,
            currentA = -18.6,
            remainingCapacityAh = 25.95,
            nominalCapacityAh = 50.0,
            fullChargeCapacityAh = 50.0,
            stateOfChargePercent = 52,
            cycleCount = 2,
            temperaturesC = listOf(33.4, 32.2, 32.2),
            cellCount = 17,
            chargeMosEnabled = true,
            dischargeMosEnabled = true,
            balancingMask = 0,
            protectionMask = 0,
            alarmMask = 0,
            softwareVersion = "8.0",
            productionDate = "2026-07-06",
            humidityPercent = null,
            balancingCurrentMa = null,
            updatedAtMillis = now
        ),
        cells = CellSummary(
            millivolts = listOf(
                3276, 3279, 3277, 3275, 3277, 3279, 3276, 3276, 3277,
                3278, 3276, 3277, 3276, 3277, 3276, 3278, 3277
            ),
            updatedAtMillis = now
        ),
        dataFreshness = DataFreshness.Fresh,
        communicationReadyAtMillis = now - 12_000,
        lastValidDataAtMillis = now,
        lastDataAgeSeconds = 0,
        locationPermissionGranted = true,
        gpsSpeed = GpsSpeedState(
            currentKmh = 28.6,
            average5SecondsKmh = 28.1,
            maximumKmh = 45.6
        ),
        trip = TripState(
            isTracking = true,
            startedAtMillis = now - 2_760_000,
            distanceMeters = 12_600.0,
            startSocPercent = 64,
            currentSocPercent = 52,
            startRemainingAh = 32.0,
            currentRemainingAh = 25.95,
            integratedConsumedAh = 6.05,
            integratedConsumedWh = 332.8,
            currentSpeedKmh = 28.6,
            locationAccuracyMeters = 4.8f,
            validLocationPoints = 2_184,
            lastLocationAtMillis = now,
            gpsMessage = "GPS 行程记录中",
            speedRangeStats = listOf(
                SpeedRangeStats(25, 1_200.0, 173.0, 0.38, 21.0),
                SpeedRangeStats(30, 1_200.0, 151.0, 0.52, 28.0),
                SpeedRangeStats(35, 1_800.0, 185.0, 0.75, 40.0),
                SpeedRangeStats(40, 4_200.0, 378.0, 1.85, 102.0),
                SpeedRangeStats(45, 2_100.0, 168.0, 1.05, 60.0),
                SpeedRangeStats(50, 1_200.0, 86.4, 0.70, 45.0),
                SpeedRangeStats(55, 600.0, 39.0, 0.45, 25.0),
                SpeedRangeStats(60, 300.0, 18.0, 0.35, 11.8)
            ),
            brakeTest = BrakeTestState(
                targetSpeedKmh = 40,
                phase = BrakeTestPhase.Complete,
                currentSpeedKmh = 0.0,
                startSpeedKmh = 40.0,
                brakingDistanceMeters = 13.8,
                brakingDurationSeconds = 3.1,
                averageDecelerationMps2 = 3.58,
                sampleRateHz = 8.6,
                speedAccuracyKmh = 1.4,
                message = "测试完成"
            )
        ),
        logs = listOf(
            RawLogEntry(now - 2_000, RawLogEntry.Direction.Info, "", "测试模式：已载入17串演示数据"),
            RawLogEntry(now - 1_500, RawLogEntry.Direction.Tx, "DD A5 03 00 FF FD 77", "读取基本状态"),
            RawLogEntry(now - 1_000, RawLogEntry.Direction.Rx, "DD 03 00 23 ... 77", "BMS 响应"),
            RawLogEntry(now - 500, RawLogEntry.Direction.Info, "", "协议识别结果：JBD DD/77（V12扩展）")
        )
    )
}
