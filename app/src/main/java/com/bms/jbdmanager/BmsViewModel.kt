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

//MARK:电池状态模型
//BmsViewModel 是页面唯一状态持有者，协调蓝牙数据、GPS 行程、安全警报、历史存储、报告和应用更新。
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
            // TripTracker 是后台服务与界面的共享事实来源；这里统一映射为 UI 状态，避免两处各自累计里程。
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
            // 数据新鲜度按时钟更新，而不是依赖下一包蓝牙数据；断流后界面才能及时显示“已过期”。
            while (isActive) {
                delay(1_000)
                updateDataFreshness()
            }
        }
        checkForAppUpdate(silent = true)
        loadBatteryTrend(BatteryTrendRange.OneDay)
    }

    //MARK:更新权限状态
    //setPermissionsGranted 更新附近设备权限状态；权限刚获得时立即重新读取手机蓝牙开关状态。
    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) bleManager.refreshBluetoothState()
    }

    //MARK:设置定位
    //setLocationPermissionGranted 同步精确定位权限；获得后启动行程服务，失去时停止正在记录的 GPS 行程。
    fun setLocationPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(locationPermissionGranted = granted) }
        if (granted) {
            if (TripTracker.state.value.isTracking && TripTracker.state.value.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms) {
                if (!ensureTripTrackingService()) {
                    TripTracker.finish("无法启动后台定位")
                    onError("GPS 行程服务启动失败，请保持 App 在前台后重试")
                }
            } else {
                startOrUpdateTripTracking()
            }
        } else if (TripTracker.state.value.isTracking) {
            TripTracker.finish("精确位置权限不可用，行程已停止")
            getApplication<Application>().stopService(tripServiceIntent)
        }
    }

    //MARK:设置温度
    //setTemperatureEmergencyPermissions 保存全屏警报和悬浮窗权限结果，供温度危险提醒选择展示方式。
    fun setTemperatureEmergencyPermissions(fullScreenGranted: Boolean, overlayGranted: Boolean) {
        _uiState.update {
            it.copy(
                fullScreenTemperatureAlertGranted = fullScreenGranted,
                overlayTemperatureAlertGranted = overlayGranted
            )
        }
    }

    //MARK:刷新蓝牙
    //refreshBluetoothState 要求蓝牙管理器重新报告硬件支持与开关状态，不直接开始扫描。
    fun refreshBluetoothState() = bleManager.refreshBluetoothState()

    //MARK:开始扫描
    //startScan 检查权限和当前连接状态后开始附近设备扫描，并清空已过期的临时扫描结果。
    fun startScan() {
        if (TripTracker.state.value.isTracking && TripTracker.state.value.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms) {
            onError("请先结束当前 GPS 行程")
            return
        }
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

    //MARK:停止扫描
    //stopScan 停止正在进行的 BLE 扫描并取消扫描超时任务，保留已经发现的设备。
    fun stopScan() = bleManager.stopScan()

    //MARK:连接设备
    //connect 根据蓝牙地址建立新连接，并在连接前重置上一会话的临时通信状态。
    fun connect(address: String) {
        if (TripTracker.state.value.isTracking && TripTracker.state.value.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms) {
            onError("请先结束当前 GPS 行程")
            return
        }
        autoConnectAttempted = true
        manualDisconnect = false
        cancelReconnect()
        reconnectAttempt = 0
        _uiState.update { it.copy(errorMessage = null) }
        frameAssembler.clear()
        bleManager.connect(address)
    }

    //MARK:开始里程行程
    //保存当前电池快照后结束已有行程，启动纯 GPS 里程记录与前台定位服务，并主动断开 BMS 蓝牙以进入租用电池模式。
    fun startMileageOnlyTrip(): Boolean {
        val application = getApplication<Application>()
        if (!_uiState.value.locationPermissionGranted) {
            onError("请先允许精确位置权限")
            return false
        }
        if (TripTracker.state.value.isTracking && TripTracker.state.value.isMileageOnly) {
            return ensureTripTrackingService()
        }

        saveLastSnapshot()
        if (TripTracker.state.value.isTracking) {
            TripTracker.finish("已切换为仅 GPS 行程")
        }
        gpsSpeedTracker.reset()
        TripTracker.resetAutoStartSuppression()
        TripTracker.beginMileageOnly()
        if (!ensureTripTrackingService()) {
            TripTracker.finish("无法启动后台定位")
            application.stopService(tripServiceIntent)
            onError("GPS 行程服务启动失败，请保持 App 在前台后重试")
            return false
        }

        manualDisconnect = true
        autoConnectAttempted = true
        cancelReconnect()
        reconnectAttempt = 0
        bleManager.stopScan()
        val shouldDisconnectBle = _uiState.value.phase !in setOf(
            ConnectionPhase.Idle,
            ConnectionPhase.Error
        )
        _uiState.update {
            it.copy(
                phase = if (shouldDisconnectBle) ConnectionPhase.Disconnecting else ConnectionPhase.Idle,
                isScanning = false,
                reconnectAttempt = 0,
                reconnectInSeconds = null,
                errorMessage = null
            )
        }
        if (shouldDisconnectBle) {
            bleManager.disconnect()
        } else {
            manualDisconnect = false
            _uiState.update { it.copy(connectedAddress = null, connectedName = null) }
        }
        return true
    }

    //MARK:结束里程行程
    //仅在纯 GPS 行程运行时归档本段里程，停止定位服务并把页面中的实时速度清零。
    fun finishMileageOnlyTrip() {
        val trip = TripTracker.state.value
        if (!trip.isTracking || !trip.isMileageOnly) return
        gpsSpeedTracker.reset()
        TripTracker.finish("GPS 行程已结束")
        getApplication<Application>().stopService(tripServiceIntent)
        _uiState.update { it.copy(gpsSpeed = GpsSpeedState()) }
    }

    //MARK:开始自行车
    //结束并归档其他活动行程后启动自行车 GPS 记录；该模式主动断开 BMS，避免自动连接覆盖骑行页面。
    fun startBicycleTrip(): Boolean {
        val application = getApplication<Application>()
        if (!_uiState.value.locationPermissionGranted) {
            onError("请先允许精确位置权限")
            return false
        }
        if (TripTracker.state.value.isTracking && TripTracker.state.value.isBicycle) {
            return ensureTripTrackingService()
        }
        saveLastSnapshot()
        if (TripTracker.state.value.isTracking) TripTracker.finish("已切换为自行车骑行")
        gpsSpeedTracker.reset()
        TripTracker.resetAutoStartSuppression()
        TripTracker.beginBicycle()
        if (!ensureTripTrackingService()) {
            TripTracker.finish("无法启动后台定位")
            application.stopService(tripServiceIntent)
            onError("自行车行程服务启动失败，请保持 App 在前台后重试")
            return false
        }
        manualDisconnect = true
        autoConnectAttempted = true
        cancelReconnect()
        reconnectAttempt = 0
        bleManager.stopScan()
        val shouldDisconnectBle = _uiState.value.phase !in setOf(ConnectionPhase.Idle, ConnectionPhase.Error)
        _uiState.update {
            it.copy(
                phase = if (shouldDisconnectBle) ConnectionPhase.Disconnecting else ConnectionPhase.Idle,
                isScanning = false,
                reconnectAttempt = 0,
                reconnectInSeconds = null,
                errorMessage = null
            )
        }
        if (shouldDisconnectBle) bleManager.disconnect() else {
            manualDisconnect = false
            _uiState.update { it.copy(connectedAddress = null, connectedName = null) }
        }
        return true
    }

    //MARK:结束自行车
    //归档自行车距离、有效骑行时间和估算热量，然后停止后台定位与实时速度更新。
    fun finishBicycleTrip() {
        val trip = TripTracker.state.value
        if (!trip.isTracking || !trip.isBicycle) return
        gpsSpeedTracker.reset()
        TripTracker.finish("自行车骑行已结束")
        getApplication<Application>().stopService(tripServiceIntent)
        // 归档完成后同步读取最终状态和历史，避免等待异步状态收集时短暂显示“今天没有记录”。
        val finishedTrip = TripTracker.state.value
        _uiState.update {
            it.copy(
                trip = finishedTrip,
                gpsSpeed = GpsSpeedState(),
                mileageHistory = buildMileageHistory(finishedTrip)
            )
        }
    }

    //MARK:设置体重
    //保存自行车热量估算体重，当前骑行后续采样与下一次骑行都会使用新值。
    fun setBicycleBodyWeight(weightKg: Double) = TripTracker.setBicycleBodyWeight(weightKg)

    //MARK:重置换电行程
    //把当前换电里程归档后立即开始新的一段，沿用原倒计时目标；定位服务无法继续时结束新行程并提示错误。
    fun resetMileageOnlyTrip() {
        val trip = TripTracker.state.value
        if (!trip.isTracking || !trip.isMileageOnly) return
        val countdownTargetKm = trip.mileageCountdownTargetKm
        TripTracker.finish("已更换电池，上一段行程已保存")
        gpsSpeedTracker.reset()
        TripTracker.beginMileageOnly(countdownTargetKm = countdownTargetKm)
        if (!ensureTripTrackingService()) {
            TripTracker.finish("无法继续后台定位")
            onError("GPS 行程服务启动失败，请保持 App 在前台后重试")
        }
        _uiState.update { it.copy(gpsSpeed = GpsSpeedState()) }
    }

    //MARK:设置倒计时
    //setMileageCountdownTarget 把纯 GPS 行程的换电提醒目标限制在允许范围，并重新判断当前里程是否已经达到。
    fun setMileageCountdownTarget(targetKm: Int) {
        TripTracker.setMileageCountdownTarget(targetKm)
    }

    //MARK:确认倒计时
    //acknowledgeMileageCountdown 确认本段换电里程提醒，保留已达到时间但避免同一段行程再次通知。
    fun acknowledgeMileageCountdown() {
        TripTracker.acknowledgeMileageCountdown()
    }

    //MARK:断开设备
    //disconnect 主动断开当前蓝牙连接并清理命令队列，使后续不会自动沿用本次会话。
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

    //MARK:一键退出
    //保存最后状态并取消扫描、重连、GPS、温度警报和后台服务，再断开蓝牙，作为 App 的完整退出入口。
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

    //MARK:提交蓝牙密码
    //submitBluetoothPassword 校验六位数字读取密码，绑定当前设备地址后交给蓝牙认证状态机发送。
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

    //MARK:关闭错误
    //dismissError 清除当前用户可见错误信息，不改变连接、行程或下载状态。
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    //MARK:检查更新
    //checkForAppUpdate 后台获取版本清单并执行更新策略；静默检查失败时不打扰用户。
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

    //MARK:显示更新
    //showAppUpdatePrompt 把已经获取到的可用更新切换为可见弹框，供用户查看说明或开始下载。
    fun showAppUpdatePrompt() {
        if (_uiState.value.appUpdate.available != null) {
            _uiState.update {
                it.copy(appUpdate = it.appUpdate.copy(showPrompt = true, statusMessage = null))
            }
        } else {
            checkForAppUpdate(silent = false)
        }
    }

    //MARK:关闭更新
    //dismissAppUpdate 关闭非强制更新弹框；强制更新状态下不允许通过该入口绕过升级。
    fun dismissAppUpdate() {
        val update = _uiState.value.appUpdate
        if (update.available?.forceUpdate == true && update.apkFilePath == null) return
        if (update.downloading) downloadJob?.cancel()
        _uiState.update {
            it.copy(appUpdate = it.appUpdate.copy(showPrompt = false, downloading = false))
        }
    }

    //MARK:跳过当前版本
    //skipAppUpdate 记录用户跳过的可选版本并关闭提示；更高版本和强制更新仍可再次出现。
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

    //MARK:下载新版
    //优先复用已下载的 APK；否则在后台下载最新版本，持续更新进度，完成后触发安装请求，失败时保留错误信息。
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

    //MARK:重试安装
    //retryAppUpdateInstall 在未知来源安装权限获得后，重新发布已下载 APK 的安装请求。
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

    //MARK:关闭更新状态
    //dismissAppUpdateStatus 关闭更新下载或失败状态提示，但不取消系统已经开始的安装流程。
    fun dismissAppUpdateStatus() {
        _uiState.update { it.copy(appUpdate = it.appUpdate.copy(statusMessage = null)) }
    }

    //MARK:关闭安全警报
    //dismissTemperatureSafetyAlert 关闭可取消的应用内温度提示；危险外部警报仍需通过确认入口处理。
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

    //MARK:确认安全警报
    //acknowledgeTemperatureSafetyAlert 按告警 ID 确认当前温度危险提醒，关闭外部警报并防止旧提醒重复出现。
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

    //MARK:测试高温警报
    //testCriticalTemperatureAlert 仅在调试入口构造 62.5℃危险样本，用于验证全屏、声音、振动和通知效果。
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

    //MARK:保存快照
    //把当前界面状态写入末次快照；存储成功后立即替换内存中的 lastSnapshot，供断开后继续查看。
    fun saveLastSnapshot() {
        val snapshot = lastSnapshotStore.save(_uiState.value) ?: return
        _uiState.update { it.copy(lastSnapshot = snapshot) }
    }

    //MARK:开始续航测试
    //确认 BMS、定位权限和 GPS 行程均可用后，按目标速度开始累计该速度区间的续航样本。
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

    //MARK:结束续航测试
    //仅在续航测试正在运行时结束当前样本，并由 TripTracker 保存本次有效统计。
    fun finishRangeTest() {
        if (!_uiState.value.trip.rangeTest.isActive) return
        TripTracker.finishRangeTest()
    }

    //MARK:清空续航样本
    //clearSpeedRangeStats 清空长期分速度续航样本并立即刷新 ViewModel 中的行程状态。
    fun clearSpeedRangeStats() {
        TripTracker.clearSpeedRangeStats()
    }

    //MARK:添加健康记录
    //使用 BMS 额定容量校验实测 Ah/Wh，补充循环次数与平均温度后保存手动容量记录，并刷新健康历史。
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

    //MARK:删除健康记录
    //按记录 ID 删除一条容量健康结果，并用存储层返回的最新列表刷新页面。
    fun deleteCapacityHealthRecord(id: Long) {
        val updated = capacityHealthStore.delete(id)
        _uiState.update { it.copy(capacityHealthRecords = updated) }
    }

    //MARK:开始容量测试
    //以当前 BMS 总容量和剩余容量建立容量测试基线，保存进行中状态，供后续放电量累计和断点恢复。
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

    //MARK:结束容量测试
    //把正在运行的容量测试结算为完成状态，记录结束时间和累计放电结果并持久化。
    fun finishAutomaticCapacityTest() {
        val current = _uiState.value.automaticCapacityTest
        if (current.phase != AutomaticCapacityTestPhase.Running) return
        val completed = finishCapacityTest(current, System.currentTimeMillis())
        automaticCapacityTestStore.save(completed)
        _uiState.update { it.copy(automaticCapacityTest = completed) }
    }

    //MARK:放弃容量测试
    //discardAutomaticCapacityTest 丢弃当前自动容量测试结果并设置抑制标记，避免仍处于满电时立即重新开始。
    fun discardAutomaticCapacityTest() {
        val idle = AutomaticCapacityTestState(autoStartSuppressed = true)
        automaticCapacityTestStore.save(idle)
        _uiState.update { it.copy(automaticCapacityTest = idle) }
    }

    //MARK:保存容量测试
    //校验自动测试已经完成且放电量有效，再保存容量、覆盖率和质量结论，并清除进行中的测试状态。
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

    //MARK:读取电池趋势
    //确定当前或末次设备地址，并按所选时间范围异步读取趋势、满充指纹和压差记录；新请求会取消旧查询。
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

    //MARK:导出完整备份
    //先保存末次快照，再把全部偏好数据与长期趋势数据库写入用户选择的完整备份文件。
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

    //MARK:准备数据恢复
    //仅在蓝牙空闲时读取并校验备份，把文件复制到暂存区后生成恢复预览，尚不覆盖正式数据。
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

    //MARK:确认恢复数据
    //在蓝牙空闲时应用已校验的暂存备份；成功后重新载入全部状态，失败则保留原数据并显示原因。
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

    //MARK:取消数据恢复
    //删除待恢复的暂存文件并清空恢复预览，不修改当前正式数据。
    fun cancelDataRestore() {
        dataArchiveManager.cancelRestore(preparedDataRestore)
        preparedDataRestore = null
        _uiState.update { it.copy(dataManagement = it.dataManagement.copy(pendingRestore = null)) }
    }

    //MARK:导出数据
    //exportCsvPackage 把趋势、容量、告警和骑行记录分别转换成 CSV，并合并输出为一个 ZIP 文件。
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

    //MARK:导出报告
    //优先复制已生成的健康报告缓存；没有缓存时根据当前历史数据重新生成 PDF，并写入用户选择的位置。
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

    //MARK:预览报告
    //previewBatteryHealthPdf 在缓存目录生成临时健康报告并打开应用内预览，不写入用户选择的永久文件。
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

    //MARK:关闭预览
    //closeBatteryHealthPdfPreview 关闭 PDF 预览并删除对应缓存文件，同时保留用户此前所在的数据页面。
    fun closeBatteryHealthPdfPreview() {
        val path = _uiState.value.dataManagement.healthPdfPreviewPath
        _uiState.update {
            it.copy(dataManagement = it.dataManagement.copy(healthPdfPreviewPath = null))
        }
        path?.let { viewModelScope.launch(Dispatchers.IO) { runCatching { File(it).delete() } } }
    }

    //MARK:关闭数据状态
    //dismissDataManagementStatus 清除备份、恢复或导出的结果提示，不删除已经成功生成的文件。
    fun dismissDataManagementStatus() = _uiState.update {
        it.copy(dataManagement = it.dataManagement.copy(statusMessage = null))
    }

    //MARK:重载恢复数据
    //reloadStateAfterDataRestore 备份恢复成功后重新加载所有仓库、历史和快照，并阻止恢复旧的运行中任务。
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

    //MARK:刷新保护参数
    //refreshProtectionParams 把保护参数标记为加载中，并向 BMS 重新发送只读参数块请求。
    fun refreshProtectionParams() {
        _uiState.update { it.copy(protectionParamsLoading = true, protectionParamsError = null) }
        bleManager.readProtectionParameters()
    }

    //MARK:蓝牙状态
    //onBluetoothState 报告手机是否支持并已开启蓝牙，使上层决定是否自动扫描或连接。
    override fun onBluetoothState(supported: Boolean, enabled: Boolean) {
        _uiState.update { it.copy(bluetoothSupported = supported, bluetoothEnabled = enabled) }
        tryAutoConnect(supported, enabled)
    }

    //MARK:扫描开始
    //onScanStarted 通知上层 BLE 扫描已经开始，用于切换扫描状态和旋转图标。
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

    //MARK:扫描结果
    //onScanResult 把本次发现或更新后的附近设备列表交给上层展示。
    override fun onScanResult(devices: List<ScanDevice>) {
        _uiState.update { it.copy(devices = devices) }
    }

    //MARK:扫描停止
    //onScanStopped 通知上层扫描已停止，使页面退出扫描中状态但继续保留结果。
    override fun onScanStopped() {
        _uiState.update {
            it.copy(
                phase = if (it.phase == ConnectionPhase.Scanning) ConnectionPhase.Idle else it.phase,
                isScanning = false
            )
        }
    }

    //MARK:正在连接
    //onConnecting 报告目标设备地址和名称，使上层进入连接中状态并保存当前目标。
    override fun onConnecting(address: String, name: String) {
        // 自动重连发生在同一段行程内时保留 GPS 速度；只有真正开始新行程才清空本次最高速度。
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

    //MARK:发现服务
    //onDiscovering 报告 GATT 已连接且正在发现服务，使页面展示服务识别阶段。
    override fun onDiscovering() {
        _uiState.update { it.copy(phase = ConnectionPhase.Discovering) }
    }

    //MARK:连接就绪
    //onReady 报告可读写 BLE 通道已配置完成，并携带最终识别出的通道类型。
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

    //MARK:连接诊断
    //onConnectionDiagnostic 上报服务 UUID、写入特征和通知特征等连接诊断信息。
    override fun onConnectionDiagnostic(message: String) {
        _uiState.update { it.copy(bleChannelDetails = message) }
    }

    //MARK:连接断开
    //onDisconnected 报告连接已经断开及可选原因，由上层区分手动断开和意外掉线。
    override fun onDisconnected(reason: String?) {
        // 在清理连接字段前保存快照，确保离线页面仍能看到最后一包完整电池数据和对应设备身份。
        saveLastSnapshot()
        automaticCapacityTestStore.save(_uiState.value.automaticCapacityTest)
        val activeTrip = TripTracker.state.value
        val preserveGpsSpeed = activeTrip.isTracking && (!manualDisconnect || activeTrip.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms)
        if (!preserveGpsSpeed) {
            gpsSpeedTracker.reset()
        }
        val address = _uiState.value.connectedAddress
        val name = _uiState.value.connectedName
        val shouldReconnect = !manualDisconnect && address != null && _uiState.value.bluetoothEnabled &&
            _uiState.value.permissionsGranted
        // 意外掉线进入重连状态并让 GPS 行程继续；用户手动断开则不重连，也不保留实时速度。
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

    //MARK:数据发送
    //onPacketSent 报告已交给系统发送的命令原始字节和用途，供调试或统计使用。
    override fun onPacketSent(packet: ByteArray, note: String) = Unit

    //MARK:接收通知
    //onNotification 转交 BMS 通知特征收到的原始字节，供上层组装和解析业务报文。
    override fun onNotification(bytes: ByteArray) {
        // 新版认证帧由 BLE 管理器处理状态机；这里只记录协议类型，避免再次交给 DD/77 组帧器。
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xAA) {
            modernAuthSeen = true
            publishProtocolDiagnosis()
            return
        }
        val frames = frameAssembler.append(bytes)
        frames.forEach(::handleFrame)
    }

    //MARK:错误处理
    //onError 把无法自动恢复的蓝牙或协议错误转换为用户可见提示。
    override fun onError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    //MARK:命令超时
    //onCommandTimeout 报告未在期限内完成的命令及原因，使上层标记数据等待或参数超时。
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

    //MARK:需要认证
    //onAuthenticationRequired 通知上层当前模块要求读取密码，并触发密码输入或自动重试。
    override fun onAuthenticationRequired(message: String) {
        _uiState.update { it.copy(authenticationRequired = true, authenticationMessage = message) }
        val password = bluetoothPassword
        if (!password.isNullOrBlank() && bluetoothPasswordAddress == _uiState.value.connectedAddress && !passwordAttempted) {
            passwordAttempted = true
            bleManager.sendAuthenticationPassword(password)
        }
    }

    //MARK:认证成功
    //onAuthenticationSucceeded 报告只读认证已经完成，并携带实际使用的认证协议名称。
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

    //MARK:处理报文
    //handleFrame 严格解码一帧 DD/77 数据，完成在途命令后按消息类型更新电池、单体或参数状态。
    private fun handleFrame(raw: ByteArray) {
        // 严格解码成功后先通知命令队列结束等待，再把数据映射到业务状态；顺序不能颠倒以免阻塞轮询。
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
                // 基本信息是温度、行程、容量测试和趋势记录的共同采样时点，所有派生计算都使用同一份数据。
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
                // 基本信息与单体电压分别返回；任意一方后到时都重新尝试生成满充指纹，内部会负责去重。
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

    //MARK:更新容量测试
    //updateAutomaticCapacityTest 依据满充起点、设备地址和当前放电样本推进自动容量测试，并限频保存进度。
    private fun updateAutomaticCapacityTest(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val now = System.currentTimeMillis()
        val current = _uiState.value.automaticCapacityTest
        // 自动测试只在满足满充起点后开始；运行中还必须属于同一蓝牙地址，防止换电池后串接容量。
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

    //MARK:记录保护
    //recordProtectionTransitions 比较十五个保护位的新旧状态，为触发创建事件、为解除补齐结束时间。
    private fun recordProtectionTransitions(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val state = _uiState.value
        val address = state.connectedAddress ?: return
        val now = System.currentTimeMillis()
        var changed = false
        val updated = state.protectionEvents.toMutableList()

        for (bit in 0 until 15) {
            // 每个保护位只维护一个未结束事件：0→1 新建，1→0 补结束时间，持续为 1 不重复写历史。
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

    //MARK:检查温度
    //updateTemperatureSafety 把基本信息交给温度监控器，并根据新告警或恢复结果同步全屏提醒和 UI 状态。
    private fun updateTemperatureSafety(info: com.bms.jbdmanager.model.BmsBasicInfo) {
        val result = temperatureSafetyMonitor.update(
            info = info,
            protectionParams = _uiState.value.protectionParams
        )
        result.alert?.let { alert ->
            // 外部危险界面成功展示后仍保存告警状态，但界面层可据此避免叠加第二个应用内弹框。
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

    //MARK:记录趋势
    //recordBatteryTrendSample 按固定间隔保存总压、电流、SOC、温度和单体压差，并定期执行长期数据维护。
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

    //MARK:追加实时趋势
    //appendLiveTrendPoint 把最新趋势点合并到当前图表数据，保持时间顺序并限制页面内存中的点数。
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

    //MARK:记录满充纹
    //recordFullChargeFingerprint 在满足有效满充条件时保存当日逐串电压指纹，同一设备每天只保留一份代表记录。
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

    //MARK:检查满充压差
    //considerFullChargeDeltaOnConnect 每次连接仅尝试一次满充压差采样；只有 SOC 和电压满足满充条件才保存。
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

    //MARK:发布协议诊断
    //publishProtocolDiagnosis 根据经典帧、新版认证和 V12 扩展证据组合当前设备的协议诊断文字。
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

    //MARK:标记数据更新
    //markDataFresh 记录最近一包有效数据的时间，并把等待或过期状态恢复为实时。
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

    //MARK:保存当前设备
    //persistCurrentDevice 保存当前设备地址、最终名称和最近 SOC，并刷新首页的历史设备列表。
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

    //MARK:更新更新数据
    //updateDataFreshness 根据最后有效数据距当前时间的间隔计算实时、延迟或过期状态，并触发通信恢复。
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

    //MARK:开始更新行程
    //收到基本信息后开始或更新 BMS 行程，并确保 GPS 前台服务运行；定位权限不足时只保留电池数据。
    private fun startOrUpdateTripTracking(info: com.bms.jbdmanager.model.BmsBasicInfo? = _uiState.value.basicInfo) {
        val state = _uiState.value
        if (!state.locationPermissionGranted || state.phase != ConnectionPhase.Ready || info == null) return
        if (TripTracker.state.value.isTracking && TripTracker.state.value.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms) return
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

    //MARK:确保行程
    //ensureTripTrackingService 按启动限频和系统限制确保 GPS 前台服务正在运行，失败时返回 false 供上层处理。
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

    //MARK:结束行程
    //手动断开蓝牙时清零速度；若行程正在记录，则归档行程并停止 GPS 前台服务。
    private fun finishTripTracking() {
        gpsSpeedTracker.reset()
        _uiState.update { it.copy(gpsSpeed = GpsSpeedState()) }
        if (!TripTracker.state.value.isTracking) return
        TripTracker.finish("蓝牙已手动断开，行程结束")
        getApplication<Application>().stopService(tripServiceIntent)
    }

    //MARK:计划重连
    //scheduleReconnect 按递增等待时间安排蓝牙重连，倒计时期间持续更新页面且可被手动操作取消。
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

    //MARK:取消重连
    //取消待执行的自动重连任务、归零尝试次数，并清除页面上的重连倒计时。
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _uiState.update { it.copy(reconnectInSeconds = null) }
    }

    //MARK:刷新里程历史
    //refreshMileageHistory 重新读取已归档行程，并结合当前活动行程生成首页与历史页共用状态。
    private fun refreshMileageHistory() {
        _uiState.update { it.copy(mileageHistory = buildMileageHistory(it.trip)) }
    }

    //MARK:构建里程历史
    //buildMileageHistory 把持久化行程和当前行程组合为里程历史模型，避免活动行程被重复计入。
    private fun buildMileageHistory(trip: com.bms.jbdmanager.model.TripState): MileageHistoryState {
        val sessions = TripTracker.loadMileageSessions()
        val activeDistance = if (trip.isTracking) trip.distanceMeters else 0.0
        val activeStartedAt = if (trip.isTracking) trip.startedAtMillis else null
        return MileageHistoryState(
            sessions = sessions,
            activeTripDistanceMeters = activeDistance,
            activeTripStartedAtMillis = activeStartedAt,
            activeTripCategory = if (trip.isBicycle) com.bms.jbdmanager.model.TripCategory.Bicycle else com.bms.jbdmanager.model.TripCategory.Electric,
            activeTripMovingDurationSeconds = if (trip.isBicycle) trip.bicycleMovingDurationSeconds else 0.0,
            activeTripCaloriesKcal = if (trip.isBicycle) trip.bicycleCaloriesKcal else 0.0
        )
    }

    //MARK:清理模型
    //onCleared ViewModel 销毁时关闭蓝牙、取消任务并释放数据库等长期资源。
    override fun onCleared() {
        automaticCapacityTestStore.save(_uiState.value.automaticCapacityTest)
        reconnectJob?.cancel()
        downloadJob?.cancel()
        batteryTrendLoadJob?.cancel()
        dataArchiveManager.cancelRestore(preparedDataRestore)
        bleManager.close()
        super.onCleared()
    }

    //MARK:自动连接
    //tryAutoConnect 在蓝牙可用、权限完整且本次尚未尝试时连接最后保存的设备，否则开始扫描。
    private fun tryAutoConnect(supported: Boolean, enabled: Boolean) {
        if (TripTracker.state.value.isTracking && TripTracker.state.value.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms) return
        if (autoConnectAttempted || !supported || !enabled || !_uiState.value.permissionsGranted) return
        if (_uiState.value.phase != ConnectionPhase.Idle) return
        val saved = savedDeviceStore.load()
        val address = saved.lastAddress ?: return
        autoConnectAttempted = true
        manualDisconnect = false
        bleManager.connect(address)
    }

    //MARK:常量配置
    //定义重连退避、数据过期、趋势采样维护、报告缓存和版本清单地址等全局业务参数。
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
