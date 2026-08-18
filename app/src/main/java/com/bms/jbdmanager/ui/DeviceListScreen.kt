package com.bms.jbdmanager.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.R
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.ScanDevice

@Composable
internal fun AppHeader(state: BmsUiState, onRequestExit: () -> Unit) {
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
internal fun ScanPanel(
    state: BmsUiState,
    connect: (String) -> Unit,
    disconnect: () -> Unit,
    refreshNearby: () -> Unit,
    showDashboard: () -> Unit
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
