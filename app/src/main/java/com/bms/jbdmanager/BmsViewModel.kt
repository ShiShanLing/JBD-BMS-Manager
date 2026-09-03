package com.bms.jbdmanager

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bms.jbdmanager.ble.JbdBleListener
import com.bms.jbdmanager.ble.JbdBleManager
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.AutomaticCapacityTestPhase
import com.bms.jbdmanager.model.AutomaticCapacityTestState
import com.bms.jbdmanager.model.BatteryTrendPoint
import com.bms.jbdmanager.model.BatteryTrendRange
import com.bms.jbdmanager.model.DataExportSnapshot
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.CapacityHealthRecordSource
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.DataFreshness
import com.bms.jbdmanager.model.GpsSpeedState
import com.bms.jbdmanager.model.MileageHistoryState
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.ScanDevice
import com.bms.jbdmanager.model.TemperatureAlertLevel
import com.bms.jbdmanager.model.TemperatureSafetyAlert
import com.bms.jbdmanager.model.classifyProtectionEvent
import com.bms.jbdmanager.model.isEffectivelyFullyCharged
import com.bms.jbdmanager.model.protectionName
import com.bms.jbdmanager.model.resolveProtectionEvent
import com.bms.jbdmanager.model.finishCapacityTest
import com.bms.jbdmanager.model.shouldStartAutomaticCapacityTest
import com.bms.jbdmanager.model.startCapacityTest
import com.bms.jbdmanager.model.updateCapacityTest
import com.bms.jbdmanager.protocol.JbdFrameAssembler
import com.bms.jbdmanager.report.BatteryHealthPdfGenerator
import com.bms.jbdmanager.protocol.JbdMessage
import com.bms.jbdmanager.protocol.JbdProtocol
import com.bms.jbdmanager.storage.AppUpdateStore
import com.bms.jbdmanager.storage.AutomaticCapacityTestStore
import com.bms.jbdmanager.storage.BatteryTrendStore
import com.bms.jbdmanager.storage.DataArchiveManager
import com.bms.jbdmanager.storage.PreparedDataRestore
import com.bms.jbdmanager.storage.CapacityHealthStore
import com.bms.jbdmanager.storage.SavedDeviceStore
import com.bms.jbdmanager.storage.LastSnapshotStore
import com.bms.jbdmanager.storage.ProtectionEventStore
import com.bms.jbdmanager.safety.TemperatureAlertNotifier
import com.bms.jbdmanager.safety.TemperatureSafetyMonitor
import com.bms.jbdmanager.trip.TripTracker
import com.bms.jbdmanager.trip.TripTrackingService
import com.bms.jbdmanager.trip.shouldAttemptTripServiceStart
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
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException

class BmsViewModel(application: Application) : AndroidViewModel(application), JbdBleListener {
    private val savedDeviceStore = SavedDeviceStore(application)
    private val savedDeviceSnapshot = savedDeviceStore.load()
    private val lastSnapshotStore = LastSnapshotStore(application)
    private val appUpdateStore = AppUpdateStore(application)
    private val capacityHealthStore = CapacityHealthStore(application)
    private val automaticCapacityTestStore = AutomaticCapacityTestStore(application)
    private val protectionEventStore = ProtectionEventStore(application)
    private val batteryTrendStore = BatteryTrendStore(application)
    private val dataArchiveManager = DataArchiveManager(application, batteryTrendStore)
    private val batteryHealthPdfGenerator = BatteryHealthPdfGenerator()
    private val temperatureSafetyMonitor = TemperatureSafetyMonitor()
    private val temperatureAlertNotifier = TemperatureAlertNotifier(application)
    private val appUpdateClient = AppUpdateClient(userAgent = "JbdBmsManager/${BuildConfig.VERSION_NAME}")
    private val _uiState = MutableStateFlow(
        BmsUiState(
            savedDevices = savedDeviceSnapshot.devices,
            lastDeviceAddress = savedDeviceSnapshot.lastAddress,
            lastDeviceName = savedDeviceSnapshot.lastName,
            lastSnapshot = lastSnapshotStore.load(),
            capacityHealthRecords = capacityHealthStore.load(),
            automaticCapacityTest = automaticCapacityTestStore.load(),
            protectionEvents = protectionEventStore.load(),
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
    private var batteryTrendLoadJob: Job? = null
    private var lastTrendSampleAtMillis = 0L
    private var lastTrendMaintenanceAtMillis = 0L
    private var lastFingerprintUiRefreshAtMillis = 0L
    private var fullChargeDeltaConsideredForSession = false
    private var preparedDataRestore: PreparedDataRestore? = null
    private var lastCapacityTestPersistAtMillis = 0L
    private var lastTripServiceStartAttemptAtMillis = 0L

    init {
        TripTracker.initialize(application)
        refreshMileageHistory()
        viewModelScope.launch {
            TripTracker.state.collect { trip ->
                val gpsSpeed = if (trip.isTracking) {
                    gpsSpeedTracker.update(trip.currentSpeedKmh, trip.lastLocationAtMillis)
                } else {
                    GpsSpeedState()
                }
                _uiState.update {
                    it.copy(
                        trip = trip,
                        gpsSpeed = gpsSpeed,
                        mileageHistory = buildMileageHistory(trip)
                    )
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                updateDataFreshness()
            }
        }
        checkForAppUpdate(silent = true)
        loadBatteryTrend(BatteryTrendRange.OneDay)
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

    fun setTemperatureEmergencyPermissions(fullScreenGranted: Boolean, overlayGranted: Boolean) {
        _uiState.update {
            it.copy(
                fullScreenTemperatureAlertGranted = fullScreenGranted,
                overlayTemperatureAlertGranted = overlayGranted
            )
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
        gpsSpeedTracker.reset()
        temperatureSafetyMonitor.reset()
        temperatureAlertNotifier.cancel()
        getApplication<Application>().stopService(tripServiceIntent)
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Idle,
                isScanning = false,
                connectedAddress = null,
                reconnectAttempt = 0,
                reconnectInSeconds = null,
                gpsSpeed = GpsSpeedState(),
                temperatureSafetyAlert = null,
                temperatureAlertUsesExternalSurface = false
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
                                else -> "已经是最新版本 ${BuildConfig.VERSION_NAME}"
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

    fun dismissTemperatureSafetyAlert() {
        _uiState.value.temperatureSafetyAlert?.let {
            temperatureAlertNotifier.acknowledge(it.id)
        }
        _uiState.update {
            it.copy(
                temperatureSafetyAlert = null,
                temperatureAlertUsesExternalSurface = false
            )
        }
    }

    fun acknowledgeTemperatureSafetyAlert(alertId: Long) {
        _uiState.update { state ->
            if (alertId < 0 || state.temperatureSafetyAlert?.id == alertId) {
                state.copy(
                    temperatureSafetyAlert = null,
                    temperatureAlertUsesExternalSurface = false
                )
            } else {
                state
            }
        }
    }

    fun testCriticalTemperatureAlert() {
        if (!BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        val alert = TemperatureSafetyAlert(
            id = now,
            level = TemperatureAlertLevel.Critical,
            title = "电池高温危险（测试）",
            message = "温度正在快速上升，最高温度 62.5℃。近10秒升温速度约 13.0℃/分钟。" +
                "这是调试测试警报。真实危险时请立即停止骑行或充电，远离可燃物。",
            maximumTemperatureC = 62.5,
            warningThresholdC = 55.0,
            criticalThresholdC = 60.0,
            riseRateCPerMinute = 13.0,
            riseWindowSeconds = 10,
            triggeredAtMillis = now
        )
        val externalSurface = temperatureAlertNotifier.show(alert)
        _uiState.update {
            it.copy(
                temperatureSafetyAlert = alert,
                temperatureAlertUsesExternalSurface = externalSurface
            )
        }
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

    fun clearSpeedRangeStats() {
        TripTracker.clearSpeedRangeStats()
    }

    fun addCapacityHealthRecord(measuredAh: Double, measuredWh: Double?, note: String) {
        val info = _uiState.value.basicInfo ?: _uiState.value.lastSnapshot?.basicInfo
        val ratedCapacityAh = info?.nominalCapacityAh?.takeIf { it > 0.0 }
        if (ratedCapacityAh == null) {
            onError("尚未读取到 BMS 总容量，无法计算健康度")
            return
        }
        if (measuredAh <= 0.0 || measuredAh > ratedCapacityAh * 1.5) {
            onError("实测容量应大于 0 且不超过 BMS 总容量的 150%")
            return
        }
        if (measuredWh != null && measuredWh <= 0.0) {
            onError("实测电量必须大于 0Wh")
            return
        }
        val now = System.currentTimeMillis()
        val updated = capacityHealthStore.add(
            CapacityHealthRecord(
                id = now,
                recordedAtMillis = now,
                measuredDischargeAh = measuredAh,
                ratedCapacityAh = ratedCapacityAh,
                measuredDischargeWh = measuredWh,
                cycleCount = info?.cycleCount,
                averageTemperatureC = info?.temperaturesC?.takeIf { it.isNotEmpty() }?.average(),
                note = note.trim().take(100)
            )
        )
        _uiState.update { it.copy(capacityHealthRecords = updated) }
    }

    fun deleteCapacityHealthRecord(id: Long) {
        val updated = capacityHealthStore.delete(id)
        _uiState.update { it.copy(capacityHealthRecords = updated) }
    }

    fun startAutomaticCapacityTest() {
        val info = _uiState.value.basicInfo
        if (info == null) {
            onError("请先连接 BMS 并读取实时数据")
            return
        }
        if (info.nominalCapacityAh <= 0.0) {
            onError("BMS 总容量无效，无法开始容量测试")
            return
        }
        val test = startCapacityTest(
            info,
            System.currentTimeMillis(),
            automatically = false,
            deviceAddress = _uiState.value.connectedAddress
        )
        automaticCapacityTestStore.save(test)
        _uiState.update { it.copy(automaticCapacityTest = test) }
    }

    fun finishAutomaticCapacityTest() {
        val current = _uiState.value.automaticCapacityTest
        if (current.phase != AutomaticCapacityTestPhase.Running) return
        val completed = finishCapacityTest(current, System.currentTimeMillis())
        automaticCapacityTestStore.save(completed)
        _uiState.update { it.copy(automaticCapacityTest = completed) }
    }

    fun discardAutomaticCapacityTest() {
        val idle = AutomaticCapacityTestState(autoStartSuppressed = true)
        automaticCapacityTestStore.save(idle)
        _uiState.update { it.copy(automaticCapacityTest = idle) }
    }

    fun saveAutomaticCapacityTestResult() {
        val test = _uiState.value.automaticCapacityTest
        val rated = test.ratedCapacityAh
        if (test.phase != AutomaticCapacityTestPhase.Completed || rated == null || test.measuredDischargeAh <= 0.0) {
            onError("当前没有可保存的自动容量测试结果")
            return
        }
        val info = _uiState.value.basicInfo ?: _uiState.value.lastSnapshot?.basicInfo
        val recordedAt = test.finishedAtMillis ?: System.currentTimeMillis()
        val updated = capacityHealthStore.add(
            CapacityHealthRecord(
                id = recordedAt,
                recordedAtMillis = recordedAt,
                measuredDischargeAh = test.measuredDischargeAh,
                ratedCapacityAh = rated,
                measuredDischargeWh = test.dischargedWh.takeIf { it > 0.0 },
                cycleCount = info?.cycleCount,
                averageTemperatureC = test.averageTemperatureC,
                note = test.resultExplanation,
                source = CapacityHealthRecordSource.Automatic,
                qualifiedForHealth = test.isQualifiedForHealth,
                qualityPercent = test.coveragePercent
            )
        )
        automaticCapacityTestStore.clear()
        _uiState.update {
            it.copy(capacityHealthRecords = updated, automaticCapacityTest = AutomaticCapacityTestState())
        }
    }

    fun loadBatteryTrend(range: BatteryTrendRange) {
        batteryTrendLoadJob?.cancel()
        val state = _uiState.value
        val address = state.connectedAddress
            ?: state.lastSnapshot?.deviceAddress
            ?: state.lastDeviceAddress
        if (address == null) {
            _uiState.update {
                it.copy(
                    batteryTrend = it.batteryTrend.copy(
                        range = range,
                        points = emptyList(),
                        fullChargeFingerprints = emptyList(),
                        fullChargeDeltas = emptyList(),
                        isLoading = false,
                        message = "尚无已连接设备的趋势数据"
                    )
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        val tripStart = state.trip.startedAtMillis
            ?: state.lastSnapshot?.trip?.startedAtMillis
        val from = when (range) {
            BatteryTrendRange.CurrentTrip -> tripStart ?: now
            else -> now - (range.durationMillis ?: 0L)
        }
        _uiState.update {
            it.copy(
                batteryTrend = it.batteryTrend.copy(
                    range = range,
                    isLoading = true,
                    message = null
                )
            )
        }
        batteryTrendLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val stored = runCatching {
                Triple(
                    batteryTrendStore.query(address, from, now),
                    batteryTrendStore.loadFullChargeFingerprints(address),
                    batteryTrendStore.loadFullChargeDeltas(address)
                )
            }
            withContext(Dispatchers.Main) {
                stored.onSuccess { (loaded, fingerprints, deltas) ->
                    _uiState.update {
                        it.copy(
                            batteryTrend = it.batteryTrend.copy(
                                range = range,
                                points = loaded,
                                fullChargeFingerprints = fingerprints,
                                fullChargeDeltas = deltas,
                                isLoading = false,
                                message = if (loaded.isEmpty()) "这个时间范围还没有趋势数据" else null
                            )
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            batteryTrend = it.batteryTrend.copy(
                                range = range,
                                isLoading = false,
                                message = "趋势数据读取失败：${error.message ?: "未知错误"}"
                            )
                        )
                    }
                }
            }
        }
    }

    fun exportFullBackup(uri: Uri) {
        if (_uiState.value.dataManagement.working) return
        saveLastSnapshot()
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(working = true, operationLabel = "正在创建完整备份…", statusMessage = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(uri, "w")?.use(dataArchiveManager::createFullBackup)
                    ?: error("无法打开保存位置")
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        dataManagement = it.dataManagement.copy(
                            working = false,
                            operationLabel = null,
                            statusMessage = result.fold(
                                onSuccess = { "完整备份已保存" },
                                onFailure = { error -> "备份失败：${error.message ?: "文件不可用"}" }
                            )
                        )
                    )
                }
            }
        }
    }

    fun prepareDataRestore(uri: Uri) {
        val state = _uiState.value
        if (state.phase !in setOf(ConnectionPhase.Idle, ConnectionPhase.Error)) {
            onError("恢复数据前请先断开蓝牙并停止扫描")
            return
        }
        if (state.dataManagement.working) return
        dataArchiveManager.cancelRestore(preparedDataRestore)
        preparedDataRestore = null
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(working = true, operationLabel = "正在校验备份…", pendingRestore = null, statusMessage = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use(dataArchiveManager::prepareRestore)
                    ?: error("无法读取备份文件")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { prepared ->
                    preparedDataRestore = prepared
                    _uiState.update {
                        it.copy(
                            dataManagement = it.dataManagement.copy(
                                working = false,
                                operationLabel = null,
                                pendingRestore = prepared.preview
                            )
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            dataManagement = it.dataManagement.copy(
                                working = false,
                                operationLabel = null,
                                statusMessage = "备份校验失败：${error.message ?: "格式不正确"}"
                            )
                        )
                    }
                }
            }
        }
    }

    fun confirmDataRestore() {
        val prepared = preparedDataRestore ?: return
        val state = _uiState.value
        if (state.phase !in setOf(ConnectionPhase.Idle, ConnectionPhase.Error)) {
            onError("恢复数据前请先断开蓝牙并停止扫描")
            return
        }
        if (state.dataManagement.working) return
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(working = true, operationLabel = "正在恢复数据…", pendingRestore = null, statusMessage = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { dataArchiveManager.restore(prepared) }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) reloadStateAfterDataRestore()
                preparedDataRestore = null
                _uiState.update {
                    it.copy(
                        dataManagement = it.dataManagement.copy(
                            working = false,
                            operationLabel = null,
                            pendingRestore = null,
                            statusMessage = result.fold(
                                onSuccess = { "数据恢复成功，已重新载入" },
                                onFailure = { error -> "恢复失败：${error.message ?: "数据未修改"}" }
                            )
                        )
                    )
                }
            }
        }
    }

    fun cancelDataRestore() {
        dataArchiveManager.cancelRestore(preparedDataRestore)
        preparedDataRestore = null
        _uiState.update { it.copy(dataManagement = it.dataManagement.copy(pendingRestore = null)) }
    }

    fun exportCsvPackage(uri: Uri) {
        if (_uiState.value.dataManagement.working) return
        val state = _uiState.value
        val snapshot = DataExportSnapshot(
            capacityRecords = state.capacityHealthRecords,
            protectionEvents = state.protectionEvents,
            mileageSessions = state.mileageHistory.sessions,
            tripState = state.trip
        )
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(working = true, operationLabel = "正在导出CSV资料包…", statusMessage = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(uri, "w")?.use { output ->
                    dataArchiveManager.exportCsvPackage(output, snapshot)
                } ?: error("无法打开保存位置")
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        dataManagement = it.dataManagement.copy(
                            working = false,
                            operationLabel = null,
                            statusMessage = result.fold(
                                onSuccess = { "CSV资料包已保存" },
                                onFailure = { error -> "CSV导出失败：${error.message ?: "文件不可用"}" }
                            )
                        )
                    )
                }
            }
        }
    }

    fun exportBatteryHealthPdf(uri: Uri) {
        if (_uiState.value.dataManagement.working) return
        val cachedPreview = _uiState.value.dataManagement.healthPdfPreviewPath
            ?.let(::File)
            ?.takeIf(File::isFile)
        if (cachedPreview != null) {
            _uiState.update {
                it.copy(dataManagement = it.dataManagement.copy(
                    working = true,
                    operationLabel = "正在保存健康报告…",
                    statusMessage = null
                ))
            }
            viewModelScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
                        cachedPreview.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("无法打开保存位置")
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(dataManagement = it.dataManagement.copy(
                            working = false,
                            operationLabel = null,
                            statusMessage = result.fold(
                                onSuccess = { "电池健康报告已保存" },
                                onFailure = { error -> "报告保存失败：${error.message ?: "文件不可用"}" }
                            )
                        ))
                    }
                }
            }
            return
        }
        saveLastSnapshot()
        val state = _uiState.value
        val basicSource = if (state.basicInfo != null) state else state.lastSnapshot?.asUiState()
        if (basicSource?.basicInfo == null) {
            onError("尚未保存真实BMS数据，无法生成健康报告")
            return
        }
        val address = state.connectedAddress ?: state.lastSnapshot?.deviceAddress ?: state.lastDeviceAddress
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(working = true, operationLabel = "正在生成健康报告…", statusMessage = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val fingerprints = address?.let(batteryTrendStore::loadFullChargeFingerprints).orEmpty()
                val deltas = address?.let(batteryTrendStore::loadFullChargeDeltas).orEmpty()
                val reportState = basicSource.copy(
                    capacityHealthRecords = state.capacityHealthRecords,
                    protectionEvents = state.protectionEvents,
                    batteryTrend = state.batteryTrend.copy(
                        fullChargeFingerprints = fingerprints,
                        fullChargeDeltas = deltas
                    ),
                    lastSnapshot = state.lastSnapshot
                )
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(uri, "w")?.use { output ->
                    batteryHealthPdfGenerator.write(output, reportState)
                } ?: error("无法打开保存位置")
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        dataManagement = it.dataManagement.copy(
                            working = false,
                            operationLabel = null,
                            statusMessage = result.fold(
                                onSuccess = { "电池健康报告已保存" },
                                onFailure = { error -> "报告生成失败：${error.message ?: "文件不可用"}" }
                            )
                        )
                    )
                }
            }
        }
    }

    fun previewBatteryHealthPdf(sourceOverride: BmsUiState? = null) {
        if (_uiState.value.dataManagement.working) return
        if (sourceOverride == null) saveLastSnapshot()
        val state = sourceOverride ?: _uiState.value
        val basicSource = if (state.basicInfo != null) state else state.lastSnapshot?.asUiState()
        if (basicSource?.basicInfo == null) {
            onError("尚未保存真实BMS数据，无法生成健康报告")
            return
        }
        val address = state.connectedAddress ?: state.lastSnapshot?.deviceAddress ?: state.lastDeviceAddress
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(
                working = true,
                operationLabel = "正在生成预览…",
                statusMessage = null
            ))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val fingerprints = if (sourceOverride != null) {
                    state.batteryTrend.fullChargeFingerprints
                } else {
                    address?.let(batteryTrendStore::loadFullChargeFingerprints).orEmpty()
                }
                val deltas = if (sourceOverride != null) {
                    state.batteryTrend.fullChargeDeltas
                } else {
                    address?.let(batteryTrendStore::loadFullChargeDeltas).orEmpty()
                }
                val reportState = basicSource.copy(
                    capacityHealthRecords = state.capacityHealthRecords,
                    protectionEvents = state.protectionEvents,
                    batteryTrend = state.batteryTrend.copy(
                        fullChargeFingerprints = fingerprints,
                        fullChargeDeltas = deltas
                    ),
                    lastSnapshot = state.lastSnapshot
                )
                val directory = File(getApplication<Application>().cacheDir, "reports").apply { mkdirs() }
                val file = File(directory, "battery_health_preview.pdf")
                FileOutputStream(file).use { output -> batteryHealthPdfGenerator.write(output, reportState) }
                file.absolutePath
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        dataManagement = it.dataManagement.copy(
                            working = false,
                            operationLabel = null,
                            healthPdfPreviewPath = result.getOrNull(),
                            statusMessage = result.exceptionOrNull()?.let { error ->
                                "报告预览生成失败：${error.message ?: "文件不可用"}"
                            }
                        )
                    )
                }
            }
        }
    }

    fun closeBatteryHealthPdfPreview() {
        val path = _uiState.value.dataManagement.healthPdfPreviewPath
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(healthPdfPreviewPath = null))
        }
        path?.let { viewModelScope.launch(Dispatchers.IO) { runCatching { File(it).delete() } } }
    }

    fun dismissDataManagementStatus() = _uiState.update {
        it.copy(dataManagement = it.dataManagement.copy(statusMessage = null))
    }

    private fun reloadStateAfterDataRestore() {
        TripTracker.reloadAfterDataRestore()
        val saved = savedDeviceStore.load()
        val restoredTrip = TripTracker.state.value
        autoConnectAttempted = true
        _uiState.update {
            it.copy(
                savedDevices = saved.devices,
                lastDeviceAddress = saved.lastAddress,
                lastDeviceName = saved.lastName,
                lastSnapshot = lastSnapshotStore.load(),
                capacityHealthRecords = capacityHealthStore.load(),
                automaticCapacityTest = automaticCapacityTestStore.load(),
                protectionEvents = protectionEventStore.load(),
                trip = restoredTrip,
                gpsSpeed = GpsSpeedState(),
                mileageHistory = buildMileageHistory(restoredTrip)
            )
        }
        loadBatteryTrend(_uiState.value.batteryTrend.range)
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
        val preserveGpsSpeed = TripTracker.state.value.isTracking
        if (!preserveGpsSpeed) {
            gpsSpeedTracker.reset()
        }
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
                gpsSpeed = if (preserveGpsSpeed) it.gpsSpeed else GpsSpeedState()
            )
        }
    }

    override fun onDiscovering() {
        _uiState.update { it.copy(phase = ConnectionPhase.Discovering) }
    }

    override fun onReady(profile: String) {
        val preserveGpsSpeed = TripTracker.state.value.isTracking
        if (!preserveGpsSpeed) {
            gpsSpeedTracker.reset(TripTracker.state.value.lastLocationAtMillis)
        }
        communicationRecoveryTriggered = false
        passwordAttempted = false
        fullChargeDeltaConsideredForSession = false
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Ready,
                protocolProfile = profile,
                errorMessage = null,
                communicationReadyAtMillis = System.currentTimeMillis(),
                reconnectInSeconds = null,
                gpsSpeed = if (preserveGpsSpeed) it.gpsSpeed else GpsSpeedState(),
                protectionParamsLoading = true,
                protectionParamsError = null
            )
        }
        loadBatteryTrend(_uiState.value.batteryTrend.range)
    }

    override fun onConnectionDiagnostic(message: String) {
        _uiState.update { it.copy(bleChannelDetails = message) }
    }

    override fun onDisconnected(reason: String?) {
        saveLastSnapshot()
        automaticCapacityTestStore.save(_uiState.value.automaticCapacityTest)
        val preserveGpsSpeed = !manualDisconnect && TripTracker.state.value.isTracking
        if (!preserveGpsSpeed) {
            gpsSpeedTracker.reset()
        }
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
                gpsSpeed = if (preserveGpsSpeed) it.gpsSpeed else GpsSpeedState(),
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
                recordProtectionTransitions(message.value)
                updateTemperatureSafety(message.value)
                startOrUpdateTripTracking(message.value)
                updateAutomaticCapacityTest(message.value)
                recordBatteryTrendSample(message.value)
                recordFullChargeFingerprint()
                considerFullChargeDeltaOnConnect()
            }
            is JbdMessage.Cells -> {
                markDataFresh()
                _uiState.update { it.copy(cells = message.value) }
                recordFullChargeFingerprint()
                considerFullChargeDeltaOnConnect()
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

    private fun updateAutomaticCapacityTest(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val now = System.currentTimeMillis()
        val current = _uiState.value.automaticCapacityTest
        val updated = when {
            current.phase == AutomaticCapacityTestPhase.Idle && current.autoStartSuppressed && info.stateOfChargePercent <= 95 ->
                current.copy(autoStartSuppressed = false)
            current.phase == AutomaticCapacityTestPhase.Idle && !current.autoStartSuppressed && shouldStartAutomaticCapacityTest(info) ->
                startCapacityTest(info, now, automatically = true, deviceAddress = _uiState.value.connectedAddress)
            current.phase == AutomaticCapacityTestPhase.Running &&
                (current.deviceAddress == null || current.deviceAddress == _uiState.value.connectedAddress) ->
                updateCapacityTest(current, info, _uiState.value.trip.currentSpeedKmh, now)
            else -> current
        }
        if (updated == current) return
        _uiState.update { it.copy(automaticCapacityTest = updated) }
        if (
            updated.phase != AutomaticCapacityTestPhase.Running ||
            current.phase != updated.phase ||
            now - lastCapacityTestPersistAtMillis >= 10_000L
        ) {
            automaticCapacityTestStore.save(updated)
            lastCapacityTestPersistAtMillis = now
        }
    }

    private fun recordProtectionTransitions(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val state = _uiState.value
        val address = state.connectedAddress ?: return
        val now = System.currentTimeMillis()
        var changed = false
        val updated = state.protectionEvents.toMutableList()

        for (bit in 0 until 15) {
            val active = info.protectionMask and (1 shl bit) != 0
            val existingIndex = updated.indexOfFirst {
                it.protectionBit == bit && it.deviceAddress == address && it.isActive
            }
            if (active && existingIndex < 0) {
                val classification = classifyProtectionEvent(bit, info, state.cells)
                updated += ProtectionEvent(
                    id = now * 100 + bit,
                    protectionBit = bit,
                    title = protectionName(bit),
                    startedAtMillis = now,
                    severity = classification.severity,
                    summary = classification.summary,
                    stateOfChargePercent = info.stateOfChargePercent,
                    totalVoltageV = info.totalVoltageV,
                    currentA = info.currentA,
                    minimumCellMv = state.cells?.minimumMv,
                    maximumCellMv = state.cells?.maximumMv,
                    cellDeltaMv = state.cells?.deltaMv,
                    maximumTemperatureC = info.temperaturesC.maxOrNull(),
                    deviceAddress = address,
                    deviceName = state.connectedName
                )
                changed = true
            } else if (!active && existingIndex >= 0) {
                updated[existingIndex] = resolveProtectionEvent(updated[existingIndex], now)
                changed = true
            }
        }

        if (changed) {
            val saved = protectionEventStore.replace(updated)
            _uiState.update { it.copy(protectionEvents = saved) }
        }
    }

    private fun updateTemperatureSafety(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val result = temperatureSafetyMonitor.update(
            info = info,
            protectionParams = _uiState.value.protectionParams
        )
        result.alert?.let { alert ->
            val externalSurface = temperatureAlertNotifier.show(alert)
            _uiState.update {
                it.copy(
                    temperatureSafetyAlert = alert,
                    temperatureAlertUsesExternalSurface = externalSurface
                )
            }
        }
        if (result.recovered) {
            temperatureAlertNotifier.dismissEmergencySurface()
            _uiState.update {
                it.copy(
                    temperatureSafetyAlert = null,
                    temperatureAlertUsesExternalSurface = false
                )
            }
        }
    }

    private fun recordBatteryTrendSample(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val now = System.currentTimeMillis()
        if (now - lastTrendSampleAtMillis < TREND_SAMPLE_INTERVAL_MILLIS) return
        val state = _uiState.value
        val address = state.connectedAddress ?: return
        lastTrendSampleAtMillis = now
        val cells = state.cells
        val point = BatteryTrendPoint(
            timestampMillis = now,
            totalVoltageV = info.totalVoltageV,
            currentA = info.currentA,
            socPercent = info.stateOfChargePercent.toDouble(),
            maximumTemperatureC = info.temperaturesC.maxOrNull(),
            cellDeltaMv = cells?.deltaMv?.toDouble(),
            minimumCellMv = cells?.minimumMv?.toDouble()
        )
        val shouldMaintain = now - lastTrendMaintenanceAtMillis >= TREND_MAINTENANCE_INTERVAL_MILLIS
        if (shouldMaintain) lastTrendMaintenanceAtMillis = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                batteryTrendStore.insert(address, point)
                if (shouldMaintain) batteryTrendStore.maintain(now)
            }
            if (_uiState.value.batteryTrend.range == BatteryTrendRange.CurrentTrip) {
                withContext(Dispatchers.Main) { appendLiveTrendPoint(point) }
            }
        }
    }

    private fun appendLiveTrendPoint(point: BatteryTrendPoint) {
        _uiState.update { state ->
            val trend = state.batteryTrend
            val startedAt = state.trip.startedAtMillis ?: state.lastSnapshot?.trip?.startedAtMillis
            if (trend.range != BatteryTrendRange.CurrentTrip || startedAt == null || point.timestampMillis < startedAt) {
                state
            } else {
                state.copy(
                    batteryTrend = trend.copy(
                        points = (trend.points + point).takeLast(360),
                        message = null
                    )
                )
            }
        }
    }

    private fun recordFullChargeFingerprint() {
        val state = _uiState.value
        val address = state.connectedAddress ?: return
        val info = state.basicInfo ?: return
        val cells = state.cells ?: return
        if (info.stateOfChargePercent < 98) return
        val now = System.currentTimeMillis()
        val mayRefreshUi = now - lastFingerprintUiRefreshAtMillis >= FINGERPRINT_UI_REFRESH_INTERVAL_MILLIS
        if (mayRefreshUi) lastFingerprintUiRefreshAtMillis = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val changed = batteryTrendStore.recordFullChargeFingerprint(address, info, cells, now)
                if (changed && mayRefreshUi) batteryTrendStore.loadFullChargeFingerprints(address) else null
            }.getOrNull()?.let { fingerprints ->
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            batteryTrend = it.batteryTrend.copy(fullChargeFingerprints = fingerprints)
                        )
                    }
                }
            }
        }
    }

    private fun considerFullChargeDeltaOnConnect() {
        if (fullChargeDeltaConsideredForSession) return
        val state = _uiState.value
        val address = state.connectedAddress ?: return
        val info = state.basicInfo ?: return
        val cells = state.cells ?: return
        fullChargeDeltaConsideredForSession = true
        if (!info.isEffectivelyFullyCharged()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val deltas = runCatching {
                val changed = batteryTrendStore.recordFullChargeDelta(address, info, cells, now)
                if (changed) batteryTrendStore.loadFullChargeDeltas(address) else null
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(batteryTrend = it.batteryTrend.copy(fullChargeDeltas = deltas))
                }
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
        val startingNewTrip = !TripTracker.state.value.isTracking
        if (startingNewTrip) {
            TripTracker.begin(info)
        }
        if (!ensureTripTrackingService() && startingNewTrip) {
            TripTracker.finish("无法启动后台定位，请保持 App 在前台后重试")
            onError("GPS 行程服务启动失败，请保持 App 在前台并重新连接")
            return
        }
        TripTracker.updateBms(info)
    }

    /**
     * TripTracker 的运行状态会落盘，但 Android 可能已经终止定位服务。不能仅凭
     * isTracking 判断服务仍存在；进程恢复和蓝牙重连后必须重新确认并补启动。
     */
    private fun ensureTripTrackingService(): Boolean {
        if (TripTrackingService.isRunning) return true
        val now = System.currentTimeMillis()
        if (!shouldAttemptTripServiceStart(
                tripIsTracking = TripTracker.state.value.isTracking,
                serviceIsRunning = TripTrackingService.isRunning,
                nowMillis = now,
                lastAttemptAtMillis = lastTripServiceStartAttemptAtMillis,
                retryIntervalMillis = TRIP_SERVICE_RETRY_INTERVAL_MS
            )
        ) return true
        lastTripServiceStartAttemptAtMillis = now
        return runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                tripServiceIntent.setAction(TripTrackingService.ACTION_START)
            )
        }.isSuccess
    }

    private fun finishTripTracking() {
        gpsSpeedTracker.reset()
        _uiState.update { it.copy(gpsSpeed = GpsSpeedState()) }
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

    private fun refreshMileageHistory() {
        _uiState.update { it.copy(mileageHistory = buildMileageHistory(it.trip)) }
    }

    private fun buildMileageHistory(trip: com.bms.jbdmanager.model.TripState): MileageHistoryState {
        val sessions = TripTracker.loadMileageSessions()
        val activeDistance = if (trip.isTracking) trip.distanceMeters else 0.0
        val activeStartedAt = if (trip.isTracking) trip.startedAtMillis else null
        return MileageHistoryState(
            sessions = sessions,
            activeTripDistanceMeters = activeDistance,
            activeTripStartedAtMillis = activeStartedAt
        )
    }

    override fun onCleared() {
        automaticCapacityTestStore.save(_uiState.value.automaticCapacityTest)
        reconnectJob?.cancel()
        downloadJob?.cancel()
        batteryTrendLoadJob?.cancel()
        dataArchiveManager.cancelRestore(preparedDataRestore)
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
        private const val TREND_SAMPLE_INTERVAL_MILLIS = 10_000L
        private const val TREND_MAINTENANCE_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
        private const val FINGERPRINT_UI_REFRESH_INTERVAL_MILLIS = 60_000L
        private const val STALE_AFTER_MS = 5_000L
        private const val RECONNECT_AFTER_STALE_MS = 10_000L
        private const val TRIP_SERVICE_RETRY_INTERVAL_MS = 5_000L
        private val RECONNECT_DELAYS_SECONDS = listOf(2, 5, 10, 30)
    }
}
