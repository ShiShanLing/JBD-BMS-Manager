package com.bms.jbdmanager

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bms.jbdmanager.ble.JbdBleListener
import com.bms.jbdmanager.ble.JbdBleManager
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.RawLogEntry
import com.bms.jbdmanager.model.ScanDevice
import com.bms.jbdmanager.model.SavedDevice
import com.bms.jbdmanager.protocol.JbdFrameAssembler
import com.bms.jbdmanager.protocol.JbdMessage
import com.bms.jbdmanager.protocol.JbdProtocol
import com.bms.jbdmanager.protocol.JbdProtocol.toHex
import com.bms.jbdmanager.trip.TripTracker
import com.bms.jbdmanager.trip.TripTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BmsViewModel(application: Application) : AndroidViewModel(application), JbdBleListener {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        BmsUiState(
            savedDevices = loadSavedDevices(),
            lastDeviceAddress = preferences.getString(LAST_DEVICE_ADDRESS, null),
            lastDeviceName = preferences.getString(LAST_DEVICE_NAME, null)
        )
    )
    val uiState: StateFlow<BmsUiState> = _uiState.asStateFlow()

    private val frameAssembler = JbdFrameAssembler()
    private val bleManager = JbdBleManager(application, this)
    private val tripServiceIntent = Intent(application, TripTrackingService::class.java)
    private var autoConnectAttempted = false
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var communicationRecoveryTriggered = false
    private var bluetoothPassword: String? = null
    private var bluetoothPasswordAddress: String? = null
    private var passwordAttempted = false
    private var classicProtocolSeen = false
    private var modernAuthSeen = false
    private var v12ExtensionSeen = false
    private var lastLoggedProtocol: String? = null
    private var basicMetadataLogged = false
    private var cellMetadataLogged = false
    private val gpsSpeedSamples = ArrayDeque<Pair<Long, Double>>()
    private var lastGpsSpeedSampleAtMillis: Long? = null
    private var maximumGpsSpeedKmh = 0.0

    init {
        TripTracker.initialize(application)
        viewModelScope.launch {
            TripTracker.state.collect { trip ->
                val gpsSpeed = if (_uiState.value.phase == ConnectionPhase.Ready) {
                    updateGpsSpeed(trip.currentSpeedKmh, trip.lastLocationAtMillis)
                } else {
                    GpsSpeedState()
                }
                _uiState.update { it.copy(trip = trip, gpsSpeed = gpsSpeed) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                updateDataFreshness()
            }
        }
    }

    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) bleManager.refreshBluetoothState()
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(locationPermissionGranted = granted) }
        if (granted) {
            startOrUpdateTripTracking()
        } else if (TripTracker.state.value.isTracking) {
            TripTracker.finish("精确位置权限不可用，行程已停止")
            getApplication<Application>().stopService(tripServiceIntent)
        }
    }

    fun refreshBluetoothState() = bleManager.refreshBluetoothState()

    fun startScan() {
        if (!_uiState.value.permissionsGranted) {
            onError("请先允许附近设备权限")
            return
        }
        val keepConnection = _uiState.value.phase == ConnectionPhase.Ready
        if (!keepConnection) {
            manualDisconnect = true
            cancelReconnect()
        }
        _uiState.update { it.copy(errorMessage = null, devices = emptyList()) }
        bleManager.startScan(keepConnection)
    }

    fun stopScan() = bleManager.stopScan()

    fun connect(address: String) {
        autoConnectAttempted = true
        manualDisconnect = false
        cancelReconnect()
        reconnectAttempt = 0
        _uiState.update { it.copy(errorMessage = null) }
        frameAssembler.clear()
        bleManager.connect(address)
    }

    fun disconnect() {
        manualDisconnect = true
        cancelReconnect()
        finishTripTracking()
        if (_uiState.value.phase == ConnectionPhase.Reconnecting) {
            _uiState.update {
                it.copy(
                    phase = ConnectionPhase.Idle,
                    connectedAddress = null,
                    reconnectAttempt = 0,
                    reconnectInSeconds = null
                )
            }
            return
        }
        _uiState.update { it.copy(phase = ConnectionPhase.Disconnecting) }
        bleManager.disconnect()
    }

    fun shutdownAll() {
        manualDisconnect = true
        autoConnectAttempted = true
        cancelReconnect()
        reconnectAttempt = 0
        bleManager.stopScan()
        TripTracker.suppressUntilNextConnection("已退出并停止全部服务")
        getApplication<Application>().stopService(tripServiceIntent)
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Idle,
                isScanning = false,
                connectedAddress = null,
                reconnectAttempt = 0,
                reconnectInSeconds = null,
                gpsSpeed = GpsSpeedState()
            )
        }
        bleManager.disconnect()
    }

    fun submitBluetoothPassword(password: String): Boolean {
        if (password.length != 6 || password.any { !it.isDigit() }) {
            onError("蓝牙读取密码必须是6位数字")
            return false
        }
        bluetoothPassword = password
        bluetoothPasswordAddress = _uiState.value.connectedAddress
        passwordAttempted = true
        _uiState.update { it.copy(authenticationMessage = "正在进行只读身份认证…") }
        return bleManager.sendAuthenticationPassword(password)
    }

    fun clearLogs() = _uiState.update { it.copy(logs = emptyList()) }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    fun startRangeTest(targetSpeedKmh: Int) {
        val state = _uiState.value
        val info = state.basicInfo
        when {
            state.phase != ConnectionPhase.Ready || info == null -> onError("请先连接 BMS 并等待电池数据")
            !state.locationPermissionGranted -> onError("请先允许精确位置权限")
            !state.trip.isTracking -> onError("GPS 行程尚未开始，请重新连接设备")
            else -> {
                TripTracker.startRangeTest(targetSpeedKmh, info)
                addLog(
                    RawLogEntry.Direction.Info,
                    "",
                    "开始 ${targetSpeedKmh}km/h 续航测试，有效速度 ${targetSpeedKmh - 5}–${targetSpeedKmh + 5}km/h"
                )
            }
        }
    }

    fun finishRangeTest() {
        if (!_uiState.value.trip.rangeTest.isActive) return
        TripTracker.finishRangeTest()
        addLog(RawLogEntry.Direction.Info, "", "续航测试已结束，结果已保留")
    }

    fun armBrakeTest(targetSpeedKmh: Int) {
        val state = _uiState.value
        when {
            state.phase != ConnectionPhase.Ready -> onError("请先连接 BMS")
            !state.locationPermissionGranted -> onError("请先允许精确位置权限")
            !state.trip.isTracking -> onError("GPS 行程尚未开始")
            targetSpeedKmh !in 10..120 -> onError("目标速度请输入 10–120 km/h")
            else -> {
                TripTracker.armBrakeTest(targetSpeedKmh)
                addLog(RawLogEntry.Direction.Info, "", "刹车测试已准备：${targetSpeedKmh}km/h 到停止")
            }
        }
    }

    fun cancelBrakeTest() {
        TripTracker.cancelBrakeTest()
        addLog(RawLogEntry.Direction.Info, "", "刹车测试已取消")
    }

    fun clearSpeedRangeStats() {
        TripTracker.clearSpeedRangeStats()
        addLog(RawLogEntry.Direction.Info, "", "已清空长期累计的续航测试样本")
    }

    override fun onBluetoothState(supported: Boolean, enabled: Boolean) {
        _uiState.update { it.copy(bluetoothSupported = supported, bluetoothEnabled = enabled) }
        tryAutoConnect(supported, enabled)
    }

    override fun onScanStarted() {
        addLog(RawLogEntry.Direction.Info, "", "开始扫描附近 BLE 设备")
        _uiState.update {
            val keepConnection = it.phase == ConnectionPhase.Ready
            it.copy(
                phase = if (keepConnection) it.phase else ConnectionPhase.Scanning,
                isScanning = true,
                connectedAddress = if (keepConnection) it.connectedAddress else null,
                connectedName = if (keepConnection) it.connectedName else null,
                errorMessage = null
            )
        }
    }

    override fun onScanResult(devices: List<ScanDevice>) {
        _uiState.update { it.copy(devices = devices) }
    }

    override fun onScanStopped() {
        _uiState.update {
            it.copy(
                phase = if (it.phase == ConnectionPhase.Scanning) ConnectionPhase.Idle else it.phase,
                isScanning = false
            )
        }
    }

    override fun onConnecting(address: String, name: String) {
        resetGpsSpeed()
        TripTracker.resetAutoStartSuppression()
        if (bluetoothPasswordAddress != null && bluetoothPasswordAddress != address) {
            bluetoothPassword = null
            bluetoothPasswordAddress = null
        }
        val rememberedName = _uiState.value.savedDevices.firstOrNull { it.address == address }?.name
        val displayName = if (name == "未命名设备" && !rememberedName.isNullOrBlank()) rememberedName else name
        addLog(RawLogEntry.Direction.Info, "", "正在连接 $displayName ($address)")
        classicProtocolSeen = false
        modernAuthSeen = false
        v12ExtensionSeen = false
        lastLoggedProtocol = null
        basicMetadataLogged = false
        cellMetadataLogged = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Connecting,
                isScanning = false,
                connectedAddress = address,
                connectedName = displayName,
                modelName = null,
                protocolProfile = "正在探测",
                detectedProtocol = null,
                bleChannelDetails = null,
                chipType = null,
                basicInfo = null,
                cells = null,
                reconnectInSeconds = null,
                dataFreshness = DataFreshness.Waiting,
                communicationReadyAtMillis = null,
                lastValidDataAtMillis = null,
                authenticationRequired = false,
                authenticationMessage = null,
                gpsSpeed = GpsSpeedState()
            )
        }
    }

    override fun onDiscovering() {
        _uiState.update { it.copy(phase = ConnectionPhase.Discovering) }
        addLog(RawLogEntry.Direction.Info, "", "已连接，正在发现服务与特征")
    }

    override fun onReady(profile: String) {
        resetGpsSpeed(TripTracker.state.value.lastLocationAtMillis)
        communicationRecoveryTriggered = false
        passwordAttempted = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Ready,
                protocolProfile = profile,
                errorMessage = null,
                communicationReadyAtMillis = System.currentTimeMillis(),
                reconnectInSeconds = null,
                gpsSpeed = GpsSpeedState()
            )
        }
        addLog(RawLogEntry.Direction.Info, "", "通信就绪：$profile")
    }

    override fun onConnectionDiagnostic(message: String) {
        _uiState.update { it.copy(bleChannelDetails = message) }
        addLog(RawLogEntry.Direction.Info, "", "连接诊断：$message")
    }

    override fun onDisconnected(reason: String?) {
        resetGpsSpeed()
        val address = _uiState.value.connectedAddress
        val name = _uiState.value.connectedName
        val shouldReconnect = !manualDisconnect && address != null && _uiState.value.bluetoothEnabled &&
            _uiState.value.permissionsGranted
        frameAssembler.clear()
        _uiState.update {
            it.copy(
                phase = if (shouldReconnect) ConnectionPhase.Reconnecting else ConnectionPhase.Idle,
                connectedAddress = if (shouldReconnect) address else null,
                connectedName = if (shouldReconnect) name else it.connectedName,
                dataFreshness = DataFreshness.Stale,
                gpsSpeed = GpsSpeedState(),
                errorMessage = reason
            )
        }
        addLog(RawLogEntry.Direction.Info, "", reason?.let { "连接断开：$it" } ?: "连接已断开")
        if (shouldReconnect) address?.let(::scheduleReconnect)
        manualDisconnect = false
    }

    private fun updateGpsSpeed(currentKmh: Double, locationAtMillis: Long?): GpsSpeedState {
        val safeCurrent = currentKmh.coerceAtLeast(0.0)
        if (locationAtMillis != null && locationAtMillis != lastGpsSpeedSampleAtMillis) {
            lastGpsSpeedSampleAtMillis = locationAtMillis
            gpsSpeedSamples.addLast(locationAtMillis to safeCurrent)
            while (gpsSpeedSamples.isNotEmpty() && locationAtMillis - gpsSpeedSamples.first().first > 5_000L) {
                gpsSpeedSamples.removeFirst()
            }
        }
        if (gpsSpeedSamples.isEmpty()) return GpsSpeedState()
        val average = gpsSpeedSamples.map { it.second }.average().takeUnless { it.isNaN() } ?: 0.0
        val sampleSpanMillis = gpsSpeedSamples.last().first - gpsSpeedSamples.first().first
        if (sampleSpanMillis >= 4_000L) {
            maximumGpsSpeedKmh = maxOf(maximumGpsSpeedKmh, average)
        }
        return GpsSpeedState(safeCurrent, average, maximumGpsSpeedKmh)
    }

    private fun resetGpsSpeed(baselineLocationAtMillis: Long? = null) {
        gpsSpeedSamples.clear()
        lastGpsSpeedSampleAtMillis = baselineLocationAtMillis
        maximumGpsSpeedKmh = 0.0
    }

    override fun onPacketSent(packet: ByteArray, note: String) {
        addLog(RawLogEntry.Direction.Tx, packet.toHex(), note)
    }

    override fun onNotification(bytes: ByteArray) {
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xAA) {
            addLog(RawLogEntry.Direction.Rx, bytes.toHex(), "检测到 JBD 新版 FF AA 认证报文")
            modernAuthSeen = true
            publishProtocolDiagnosis()
            return
        }
        val frames = frameAssembler.append(bytes)
        if (frames.isEmpty() && bytes.firstOrNull()?.toInt()?.and(0xFF) != 0xDD) {
            addLog(RawLogEntry.Direction.Rx, bytes.toHex(), "非标准通知数据")
        }
        frames.forEach(::handleFrame)
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
        addLog(RawLogEntry.Direction.Error, "", message)
    }

    override fun onCommandTimeout(command: Int, note: String) {
        addLog(RawLogEntry.Direction.Error, "", note)
        if (command == JbdProtocol.BASIC_INFO || command == JbdProtocol.CELL_VOLTAGES) {
            _uiState.update { state ->
                if (state.lastValidDataAtMillis == null) state.copy(dataFreshness = DataFreshness.Waiting) else state
            }
        }
    }

    override fun onAuthenticationRequired(message: String) {
        _uiState.update { it.copy(authenticationRequired = true, authenticationMessage = message) }
        val password = bluetoothPassword
        if (!password.isNullOrBlank() && bluetoothPasswordAddress == _uiState.value.connectedAddress && !passwordAttempted) {
            passwordAttempted = true
            bleManager.sendAuthenticationPassword(password)
        }
    }

    override fun onAuthenticationSucceeded(profile: String) {
        passwordAttempted = false
        _uiState.update {
            it.copy(
                authenticationRequired = false,
                authenticationMessage = "只读身份认证成功",
                protocolProfile = "${it.protocolProfile.substringBefore(" ·")} · $profile"
            )
        }
        addLog(RawLogEntry.Direction.Info, "", "只读身份认证成功：$profile")
    }

    private fun handleFrame(raw: ByteArray) {
        addLog(RawLogEntry.Direction.Rx, raw.toHex(), "BMS 响应")
        val frame = JbdProtocol.decode(raw).getOrElse {
            onError("报文解析失败：${it.message}")
            return
        }
        classicProtocolSeen = true
        publishProtocolDiagnosis("收到命令 0x${frame.command.toString(16).uppercase().padStart(2, '0')} 的合法 DD/77 响应")
        bleManager.onProtocolResponse(frame.command, frame.status)
        val message = JbdProtocol.parse(frame).getOrElse {
            onError("数据字段解析失败：${it.message}")
            return
        }

        when (message) {
            is JbdMessage.BasicInfo -> {
                markDataFresh(message.value.stateOfChargePercent)
                val baseLength = 23 + message.value.temperaturesC.size * 2
                if (frame.data.size > baseLength) {
                    v12ExtensionSeen = true
                    publishProtocolDiagnosis("基本信息包含 V12 扩展字段，数据长度 ${frame.data.size} 字节")
                }
                if (!basicMetadataLogged) {
                    basicMetadataLogged = true
                    addLog(
                        RawLogEntry.Direction.Info,
                        "",
                        "设备状态格式：基本信息 ${frame.data.size} 字节，${message.value.cellCount} 串，" +
                            "${message.value.temperaturesC.size} 个温度探头，软件版本 ${message.value.softwareVersion}"
                    )
                }
                _uiState.update {
                    it.copy(
                        basicInfo = message.value,
                        protocolProfile = if (frame.data.size > baseLength) {
                            "${it.protocolProfile.substringBefore(" ·")} · V12 扩展状态"
                        } else it.protocolProfile
                    )
                }
                startOrUpdateTripTracking(message.value)
            }
            is JbdMessage.Cells -> {
                markDataFresh()
                if (!cellMetadataLogged) {
                    cellMetadataLogged = true
                    addLog(
                        RawLogEntry.Direction.Info,
                        "",
                        "单体电压格式：${frame.data.size} 字节，共 ${message.value.millivolts.size} 串"
                    )
                }
                val declaredCount = _uiState.value.basicInfo?.cellCount
                if (declaredCount != null && declaredCount > 0 && declaredCount != message.value.millivolts.size) {
                    addLog(
                        RawLogEntry.Direction.Error,
                        frame.raw.toHex(),
                        "串数不一致：基本信息 $declaredCount 串，电压数据 ${message.value.millivolts.size} 串"
                    )
                }
                _uiState.update { it.copy(cells = message.value) }
            }
            is JbdMessage.HardwareVersion -> {
                _uiState.update {
                    it.copy(
                        modelName = message.value,
                        connectedName = if (it.connectedName.isNullOrBlank() || it.connectedName == "未命名设备") {
                            message.value
                        } else it.connectedName
                    )
                }
                persistCurrentDevice()
                addLog(RawLogEntry.Direction.Info, "", "识别到型号：${message.value}")
            }
            is JbdMessage.ChipType -> {
                _uiState.update { it.copy(chipType = message.value) }
                addLog(RawLogEntry.Direction.Info, "", "识别到芯片方案：${message.value}")
            }
            is JbdMessage.Unsupported -> {
                val reason = when (message.status) {
                    0x80 -> "设备不支持命令"
                    0x81 -> "操作无效或需要工厂模式"
                    0x82 -> "设备报告校验错误"
                    0x83 -> "蓝牙需要密码认证"
                    else -> "状态码 0x${message.status.toString(16).uppercase()}"
                }
                addLog(RawLogEntry.Direction.Info, frame.raw.toHex(), reason)
            }
            is JbdMessage.Unknown -> addLog(
                RawLogEntry.Direction.Info,
                message.data.toHex(),
                "未知只读响应 0x${message.command.toString(16).uppercase()}"
            )
        }
        if (frame.command == JbdProtocol.PASSWORD_PAIRING && frame.status == 0) {
            passwordAttempted = false
            _uiState.update {
                it.copy(authenticationRequired = false, authenticationMessage = "只读身份认证成功")
            }
        }
    }

    private fun publishProtocolDiagnosis(detail: String? = null) {
        val protocol = when {
            classicProtocolSeen && v12ExtensionSeen && modernAuthSeen -> "JBD DD/77（V12扩展）+ FF AA新版认证"
            classicProtocolSeen && v12ExtensionSeen -> "JBD DD/77（V12扩展）"
            classicProtocolSeen && modernAuthSeen -> "JBD DD/77（标准状态帧）+ FF AA新版认证"
            classicProtocolSeen -> "JBD DD/77（标准状态帧）"
            modernAuthSeen -> "JBD FF AA新版认证（等待状态数据）"
            else -> return
        }
        _uiState.update { it.copy(detectedProtocol = protocol) }
        if (protocol != lastLoggedProtocol) {
            lastLoggedProtocol = protocol
            addLog(
                RawLogEntry.Direction.Info,
                "",
                "协议识别结果：$protocol${detail?.let { "；$it" }.orEmpty()}"
            )
        } else if (detail != null) {
            addLog(RawLogEntry.Direction.Info, "", "协议证据：$detail")
        }
    }

    private fun markDataFresh(lastSocPercent: Int? = null) {
        communicationRecoveryTriggered = false
        reconnectAttempt = 0
        persistCurrentDevice(lastSocPercent)
        _uiState.update {
            it.copy(
                dataFreshness = DataFreshness.Fresh,
                reconnectAttempt = 0,
                lastValidDataAtMillis = System.currentTimeMillis(),
                lastDataAgeSeconds = 0
            )
        }
    }

    private fun persistCurrentDevice(lastSocPercent: Int? = null) {
        val state = _uiState.value
        val address = state.connectedAddress ?: return
        val name = state.connectedName.orEmpty().ifBlank { address }
        val existing = state.savedDevices.firstOrNull { it.address == address }
        val savedSocPercent = lastSocPercent?.coerceIn(0, 100) ?: existing?.lastSocPercent
        if (
            state.lastDeviceAddress == address &&
            existing?.name == name &&
            existing.lastSocPercent == savedSocPercent
        ) return
        val savedAddresses = preferences.getStringSet(SAVED_DEVICE_ADDRESSES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(address) }
        val editor = preferences.edit()
            .putString(LAST_DEVICE_ADDRESS, address)
            .putString(LAST_DEVICE_NAME, name)
            .putStringSet(SAVED_DEVICE_ADDRESSES, savedAddresses)
            .putString("$SAVED_DEVICE_NAME_PREFIX$address", name)
        savedSocPercent?.let { editor.putInt("$SAVED_DEVICE_SOC_PREFIX$address", it) }
        editor.apply()
        _uiState.update {
            it.copy(
                lastDeviceAddress = address,
                lastDeviceName = name,
                savedDevices = listOf(SavedDevice(address, name, savedSocPercent)) +
                    it.savedDevices.filterNot { saved -> saved.address == address }
            )
        }
    }

    private fun updateDataFreshness() {
        val state = _uiState.value
        if (state.phase != ConnectionPhase.Ready) return
        val freshnessBaseline = state.lastValidDataAtMillis ?: state.communicationReadyAtMillis ?: return
        val age = System.currentTimeMillis() - freshnessBaseline
        _uiState.update { it.copy(lastDataAgeSeconds = (age / 1_000).coerceAtLeast(0).toInt()) }
        if (age >= STALE_AFTER_MS && state.dataFreshness != DataFreshness.Stale) {
            _uiState.update { it.copy(dataFreshness = DataFreshness.Stale) }
            addLog(RawLogEntry.Direction.Error, "", "超过5秒未收到有效数据，实时数据已标记为过期")
        }
        if (age >= RECONNECT_AFTER_STALE_MS && !communicationRecoveryTriggered) {
            communicationRecoveryTriggered = true
            bleManager.disconnectForCommunicationLoss("BMS 超过10秒没有返回有效数据")
        }
    }

    private fun startOrUpdateTripTracking(info: com.bms.jbdmanager.model.BmsBasicInfo? = _uiState.value.basicInfo) {
        val state = _uiState.value
        if (!state.locationPermissionGranted || state.phase != ConnectionPhase.Ready || info == null) return
        if (TripTracker.isAutoStartSuppressed()) return
        if (!TripTracker.state.value.isTracking) {
            TripTracker.begin(info)
            val serviceStarted = runCatching {
                ContextCompat.startForegroundService(
                    getApplication(),
                    tripServiceIntent.setAction(TripTrackingService.ACTION_START)
                )
            }.isSuccess
            if (!serviceStarted) {
                TripTracker.finish("无法启动后台定位，请保持 App 在前台后重试")
                onError("GPS 行程服务启动失败，请保持 App 在前台并重新连接")
                return
            }
            addLog(RawLogEntry.Direction.Info, "", "已连接 BMS，开始记录 GPS 行程")
        }
        TripTracker.updateBms(info)
    }

    private fun finishTripTracking() {
        if (!TripTracker.state.value.isTracking) return
        TripTracker.finish("蓝牙已手动断开，行程结束")
        getApplication<Application>().stopService(tripServiceIntent)
        addLog(RawLogEntry.Direction.Info, "", "已结束 GPS 行程记录")
    }

    private fun scheduleReconnect(address: String) {
        reconnectJob?.cancel()
        reconnectAttempt += 1
        val delaySeconds = RECONNECT_DELAYS_SECONDS.getOrElse(reconnectAttempt - 1) { RECONNECT_DELAYS_SECONDS.last() }
        reconnectJob = viewModelScope.launch {
            for (remaining in delaySeconds downTo 1) {
                _uiState.update {
                    it.copy(
                        phase = ConnectionPhase.Reconnecting,
                        reconnectAttempt = reconnectAttempt,
                        reconnectInSeconds = remaining
                    )
                }
                delay(1_000)
            }
            if (!manualDisconnect && _uiState.value.permissionsGranted && _uiState.value.bluetoothEnabled) {
                addLog(RawLogEntry.Direction.Info, "", "第 $reconnectAttempt 次自动重连")
                bleManager.connect(address)
            } else {
                _uiState.update {
                    it.copy(phase = ConnectionPhase.Idle, connectedAddress = null, reconnectInSeconds = null)
                }
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _uiState.update { it.copy(reconnectInSeconds = null) }
    }

    private fun addLog(direction: RawLogEntry.Direction, hex: String, note: String) {
        val entry = RawLogEntry(System.currentTimeMillis(), direction, hex, note)
        _uiState.update { state -> state.copy(logs = (listOf(entry) + state.logs).take(MAX_LOG_ENTRIES)) }
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        bleManager.close()
        super.onCleared()
    }

    private fun tryAutoConnect(supported: Boolean, enabled: Boolean) {
        if (autoConnectAttempted || !supported || !enabled || !_uiState.value.permissionsGranted) return
        if (_uiState.value.phase != ConnectionPhase.Idle) return
        val address = preferences.getString(LAST_DEVICE_ADDRESS, null) ?: return
        autoConnectAttempted = true
        manualDisconnect = false
        val name = preferences.getString(LAST_DEVICE_NAME, null).orEmpty().ifBlank { address }
        addLog(RawLogEntry.Direction.Info, "", "正在自动连接上次设备：$name")
        bleManager.connect(address)
    }

    private fun loadSavedDevices(): List<SavedDevice> {
        val lastAddress = preferences.getString(LAST_DEVICE_ADDRESS, null)
        val addresses = preferences.getStringSet(SAVED_DEVICE_ADDRESSES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { lastAddress?.let(::add) }
        return addresses.map { address ->
            SavedDevice(
                address = address,
                name = preferences.getString("$SAVED_DEVICE_NAME_PREFIX$address", null)
                    ?: (if (address == lastAddress) preferences.getString(LAST_DEVICE_NAME, null) else null)
                    ?: address,
                lastSocPercent = if (preferences.contains("$SAVED_DEVICE_SOC_PREFIX$address")) {
                    preferences.getInt("$SAVED_DEVICE_SOC_PREFIX$address", 0).coerceIn(0, 100)
                } else null
            )
        }.sortedByDescending { it.address == lastAddress }
    }

    companion object {
        private const val PREFERENCES_NAME = "jbd_bms_preferences"
        private const val LAST_DEVICE_ADDRESS = "last_device_address"
        private const val LAST_DEVICE_NAME = "last_device_name"
        private const val STALE_AFTER_MS = 5_000L
        private const val RECONNECT_AFTER_STALE_MS = 10_000L
        private const val MAX_LOG_ENTRIES = 1_000
        private val RECONNECT_DELAYS_SECONDS = listOf(2, 5, 10, 30)
        private const val SAVED_DEVICE_ADDRESSES = "saved_device_addresses"
        private const val SAVED_DEVICE_NAME_PREFIX = "saved_device_name_"
        private const val SAVED_DEVICE_SOC_PREFIX = "saved_device_soc_"
    }
}
