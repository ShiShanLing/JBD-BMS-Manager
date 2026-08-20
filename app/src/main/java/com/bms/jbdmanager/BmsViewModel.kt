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
import com.bms.jbdmanager.model.ScanDevice
import com.bms.jbdmanager.protocol.JbdFrameAssembler
import com.bms.jbdmanager.protocol.JbdMessage
import com.bms.jbdmanager.protocol.JbdProtocol
import com.bms.jbdmanager.storage.AppUpdateStore
import com.bms.jbdmanager.storage.SavedDeviceStore
import com.bms.jbdmanager.storage.LastSnapshotStore
import com.bms.jbdmanager.trip.TripTracker
import com.bms.jbdmanager.trip.TripTrackingService
import com.bms.jbdmanager.trip.GpsSpeedTracker
import com.bms.jbdmanager.update.AppUpdateClient
import com.bms.jbdmanager.update.AppUpdatePolicy
import com.bms.jbdmanager.update.AppUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class BmsViewModel(application: Application) : AndroidViewModel(application), JbdBleListener {
    private val savedDeviceStore = SavedDeviceStore(application)
    private val savedDeviceSnapshot = savedDeviceStore.load()
    private val lastSnapshotStore = LastSnapshotStore(application)
    private val appUpdateStore = AppUpdateStore(application)
    private val appUpdateClient = AppUpdateClient(userAgent = "JbdBmsManager/${BuildConfig.VERSION_NAME}")
    private val _uiState = MutableStateFlow(
        BmsUiState(
            savedDevices = savedDeviceSnapshot.devices,
            lastDeviceAddress = savedDeviceSnapshot.lastAddress,
            lastDeviceName = savedDeviceSnapshot.lastName,
            lastSnapshot = lastSnapshotStore.load(),
            appUpdate = AppUpdateState(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE
            )
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
    private val gpsSpeedTracker = GpsSpeedTracker()
    private var downloadJob: Job? = null

    init {
        TripTracker.initialize(application)
        viewModelScope.launch {
            TripTracker.state.collect { trip ->
                val gpsSpeed = if (_uiState.value.phase == ConnectionPhase.Ready) {
                    gpsSpeedTracker.update(trip.currentSpeedKmh, trip.lastLocationAtMillis)
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
        checkForAppUpdate(silent = true)
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
        saveLastSnapshot()
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
        saveLastSnapshot()
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

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    fun checkForAppUpdate(silent: Boolean = false, allowPrompt: Boolean = true) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(appUpdate = it.appUpdate.copy(checking = true, statusMessage = null, checkError = null))
            }
            runCatching {
                withContext(Dispatchers.IO) { appUpdateClient.fetchLatest() }
            }.onSuccess { info ->
                val newer = info.versionCode > BuildConfig.VERSION_CODE
                val prompt = allowPrompt && AppUpdatePolicy.shouldPrompt(
                    info = info,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    skippedVersionCode = appUpdateStore.skippedVersionCode()
                )
                _uiState.update { state ->
                    state.copy(
                        appUpdate = state.appUpdate.copy(
                            latest = info,
                            available = info.takeIf { newer },
                            showPrompt = prompt,
                            checking = false,
                            checkError = null,
                            statusMessage = when {
                                silent -> null
                                newer -> null
                                else -> "当前已是最新版本 ${BuildConfig.VERSION_NAME}"
                            }
                        )
                    )
                }
                if (!silent && newer && allowPrompt) {
                    _uiState.update { it.copy(appUpdate = it.appUpdate.copy(showPrompt = true)) }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { state ->
                    state.copy(
                        appUpdate = state.appUpdate.copy(
                            checking = false,
                            checkError = error.message ?: "网络异常",
                            statusMessage = if (silent) null else "检查更新失败：${error.message ?: "网络异常"}"
                        )
                    )
                }
            }
        }
    }

    fun showAppUpdatePrompt() {
        if (_uiState.value.appUpdate.available != null) {
            _uiState.update {
                it.copy(appUpdate = it.appUpdate.copy(showPrompt = true, statusMessage = null))
            }
        } else {
            checkForAppUpdate(silent = false)
        }
    }

    fun dismissAppUpdate() {
        val update = _uiState.value.appUpdate
        if (update.available?.forceUpdate == true && update.apkFilePath == null) return
        if (update.downloading) downloadJob?.cancel()
        _uiState.update {
            it.copy(appUpdate = it.appUpdate.copy(showPrompt = false, downloading = false))
        }
    }

    fun skipAppUpdate() {
        val available = _uiState.value.appUpdate.available ?: return
        if (available.forceUpdate) return
        appUpdateStore.skip(available.versionCode)
        downloadJob?.cancel()
        _uiState.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    showPrompt = false,
                    downloading = false,
                    apkFilePath = null
                )
            )
        }
    }

    fun startAppUpdateDownload() {
        val info = _uiState.value.appUpdate.available
            ?: _uiState.value.appUpdate.latest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            ?: return
        val existing = _uiState.value.appUpdate.apkFilePath
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L }
        if (existing != null) {
            _uiState.update {
                it.copy(
                    appUpdate = it.appUpdate.copy(
                        installRequestId = it.appUpdate.installRequestId + 1
                    )
                )
            }
            return
        }
        if (_uiState.value.appUpdate.downloading) return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val destination = File(getApplication<Application>().cacheDir, "updates/latest.apk")
            _uiState.update {
                it.copy(
                    appUpdate = it.appUpdate.copy(
                        downloading = true,
                        progressPercent = 0,
                        statusMessage = null,
                        apkFilePath = null
                    )
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    appUpdateClient.download(info.apkUrl, destination) { percent ->
                        _uiState.update { state ->
                            state.copy(appUpdate = state.appUpdate.copy(progressPercent = percent))
                        }
                    }
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        appUpdate = it.appUpdate.copy(
                            downloading = false,
                            progressPercent = 100,
                            apkFilePath = destination.absolutePath,
                            installRequestId = it.appUpdate.installRequestId + 1
                        )
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        appUpdate = it.appUpdate.copy(
                            downloading = false,
                            statusMessage = "下载失败：${error.message ?: "网络异常"}"
                        )
                    )
                }
            }
        }
    }

    fun retryAppUpdateInstall() {
        val path = _uiState.value.appUpdate.apkFilePath ?: return
        if (!File(path).isFile) return
        _uiState.update {
            it.copy(
                appUpdate = it.appUpdate.copy(
                    installRequestId = it.appUpdate.installRequestId + 1
                )
            )
        }
    }

    fun dismissAppUpdateStatus() {
        _uiState.update { it.copy(appUpdate = it.appUpdate.copy(statusMessage = null)) }
    }

    fun saveLastSnapshot() {
        val snapshot = lastSnapshotStore.save(_uiState.value) ?: return
        _uiState.update { it.copy(lastSnapshot = snapshot) }
    }

    fun startRangeTest(targetSpeedKmh: Int) {
        val state = _uiState.value
        val info = state.basicInfo
        when {
            state.phase != ConnectionPhase.Ready || info == null -> onError("请先连接 BMS 并等待电池数据")
            !state.locationPermissionGranted -> onError("请先允许精确位置权限")
            !state.trip.isTracking -> onError("GPS 行程尚未开始，请重新连接设备")
            else -> {
                TripTracker.startRangeTest(targetSpeedKmh, info)
            }
        }
    }

    fun finishRangeTest() {
        if (!_uiState.value.trip.rangeTest.isActive) return
        TripTracker.finishRangeTest()
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
            }
        }
    }

    fun cancelBrakeTest() {
        TripTracker.cancelBrakeTest()
    }

    fun clearSpeedRangeStats() {
        TripTracker.clearSpeedRangeStats()
    }

    fun refreshProtectionParams() {
        _uiState.update { it.copy(protectionParamsLoading = true, protectionParamsError = null) }
        bleManager.readProtectionParameters()
    }

    override fun onBluetoothState(supported: Boolean, enabled: Boolean) {
        _uiState.update { it.copy(bluetoothSupported = supported, bluetoothEnabled = enabled) }
        tryAutoConnect(supported, enabled)
    }

    override fun onScanStarted() {
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
        gpsSpeedTracker.reset()
        TripTracker.resetAutoStartSuppression()
        if (bluetoothPasswordAddress != null && bluetoothPasswordAddress != address) {
            bluetoothPassword = null
            bluetoothPasswordAddress = null
        }
        val rememberedName = _uiState.value.savedDevices.firstOrNull { it.address == address }?.name
        val displayName = if (name == "未命名设备" && !rememberedName.isNullOrBlank()) rememberedName else name
        classicProtocolSeen = false
        modernAuthSeen = false
        v12ExtensionSeen = false
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
                protectionParams = null,
                protectionParamsLoading = false,
                protectionParamsError = null,
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
    }

    override fun onReady(profile: String) {
        gpsSpeedTracker.reset(TripTracker.state.value.lastLocationAtMillis)
        communicationRecoveryTriggered = false
        passwordAttempted = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Ready,
                protocolProfile = profile,
                errorMessage = null,
                communicationReadyAtMillis = System.currentTimeMillis(),
                reconnectInSeconds = null,
                gpsSpeed = GpsSpeedState(),
                protectionParamsLoading = true,
                protectionParamsError = null
            )
        }
    }

    override fun onConnectionDiagnostic(message: String) {
        _uiState.update { it.copy(bleChannelDetails = message) }
    }

    override fun onDisconnected(reason: String?) {
        saveLastSnapshot()
        gpsSpeedTracker.reset()
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
        if (shouldReconnect) address?.let(::scheduleReconnect)
        manualDisconnect = false
    }

    override fun onPacketSent(packet: ByteArray, note: String) = Unit

    override fun onNotification(bytes: ByteArray) {
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xAA) {
            modernAuthSeen = true
            publishProtocolDiagnosis()
            return
        }
        val frames = frameAssembler.append(bytes)
        frames.forEach(::handleFrame)
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onCommandTimeout(command: Int, note: String) {
        if (command == JbdProtocol.BASIC_INFO || command == JbdProtocol.CELL_VOLTAGES) {
            _uiState.update { state ->
                if (state.lastValidDataAtMillis == null) state.copy(dataFreshness = DataFreshness.Waiting) else state
            }
        }
        if (command == JbdProtocol.READ_PARAMETERS) {
            _uiState.update {
                it.copy(
                    protectionParamsLoading = false,
                    protectionParamsError = it.protectionParamsError ?: "读取保护参数超时"
                )
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
    }

    private fun handleFrame(raw: ByteArray) {
        val frame = JbdProtocol.decode(raw).getOrElse {
            onError("报文解析失败：${it.message}")
            return
        }
        classicProtocolSeen = true
        publishProtocolDiagnosis()
        bleManager.onProtocolResponse(frame.command, frame.status)
        val message = JbdProtocol.parse(frame).getOrElse { error ->
            if (frame.command == JbdProtocol.READ_PARAMETERS) {
                _uiState.update {
                    it.copy(
                        protectionParamsLoading = false,
                        protectionParamsError = "保护参数解析失败：${error.message}"
                    )
                }
            }
            onError("数据字段解析失败：${error.message}")
            return
        }

        when (message) {
            is JbdMessage.BasicInfo -> {
                markDataFresh(message.value.stateOfChargePercent)
                val baseLength = 23 + message.value.temperaturesC.size * 2
                if (frame.data.size > baseLength) {
                    v12ExtensionSeen = true
                    publishProtocolDiagnosis()
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
            }
            is JbdMessage.ChipType -> {
                _uiState.update { it.copy(chipType = message.value) }
            }
            is JbdMessage.ProtectionParams -> {
                _uiState.update {
                    it.copy(
                        protectionParams = message.value,
                        protectionParamsLoading = false,
                        protectionParamsError = null
                    )
                }
            }
            is JbdMessage.Unsupported -> {
                if (frame.command == JbdProtocol.READ_PARAMETERS) {
                    _uiState.update {
                        it.copy(
                            protectionParamsLoading = false,
                            protectionParamsError = "此 BMS 未返回保护参数（状态 ${message.status}）"
                        )
                    }
                }
            }
            is JbdMessage.Unknown -> {
                if (frame.command == JbdProtocol.READ_PARAMETERS) {
                    _uiState.update {
                        it.copy(
                            protectionParamsLoading = false,
                            protectionParamsError = "保护参数格式无法识别"
                        )
                    }
                }
            }
        }
        if (frame.command == JbdProtocol.PASSWORD_PAIRING && frame.status == 0) {
            passwordAttempted = false
            _uiState.update {
                it.copy(authenticationRequired = false, authenticationMessage = "只读身份认证成功")
            }
        }
    }

    private fun publishProtocolDiagnosis() {
        val protocol = when {
            classicProtocolSeen && v12ExtensionSeen && modernAuthSeen -> "JBD DD/77（V12扩展）+ FF AA新版认证"
            classicProtocolSeen && v12ExtensionSeen -> "JBD DD/77（V12扩展）"
            classicProtocolSeen && modernAuthSeen -> "JBD DD/77（标准状态帧）+ FF AA新版认证"
            classicProtocolSeen -> "JBD DD/77（标准状态帧）"
            modernAuthSeen -> "JBD FF AA新版认证（等待状态数据）"
            else -> return
        }
        _uiState.update { it.copy(detectedProtocol = protocol) }
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
        val snapshot = savedDeviceStore.save(address, name, savedSocPercent)
        _uiState.update {
            it.copy(
                lastDeviceAddress = snapshot.lastAddress,
                lastDeviceName = snapshot.lastName,
                savedDevices = snapshot.devices
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
        }
        TripTracker.updateBms(info)
    }

    private fun finishTripTracking() {
        if (!TripTracker.state.value.isTracking) return
        TripTracker.finish("蓝牙已手动断开，行程结束")
        getApplication<Application>().stopService(tripServiceIntent)
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

    override fun onCleared() {
        reconnectJob?.cancel()
        downloadJob?.cancel()
        bleManager.close()
        super.onCleared()
    }

    private fun tryAutoConnect(supported: Boolean, enabled: Boolean) {
        if (autoConnectAttempted || !supported || !enabled || !_uiState.value.permissionsGranted) return
        if (_uiState.value.phase != ConnectionPhase.Idle) return
        val saved = savedDeviceStore.load()
        val address = saved.lastAddress ?: return
        autoConnectAttempted = true
        manualDisconnect = false
        bleManager.connect(address)
    }

    companion object {
        private const val STALE_AFTER_MS = 5_000L
        private const val RECONNECT_AFTER_STALE_MS = 10_000L
        private val RECONNECT_DELAYS_SECONDS = listOf(2, 5, 10, 30)
    }
}
