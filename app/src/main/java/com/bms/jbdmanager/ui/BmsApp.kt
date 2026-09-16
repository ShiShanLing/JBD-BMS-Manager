package com.bms.jbdmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bms.jbdmanager.BuildConfig
import com.bms.jbdmanager.BmsViewModel
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.TemperatureAlertLevel

@Composable
//MARK:应用入口
//BmsApp 绘制应用组件，并根据参数决定文案、数值、颜色及交互状态。
fun BmsApp(
    viewModel: BmsViewModel,
    exitApp: () -> Unit,
    requestPermissions: () -> Unit,
    requestEnableBluetooth: () -> Unit,
    requestLocationPermission: () -> Unit,
    requestFullScreenTemperaturePermission: () -> Unit,
    requestOverlayTemperaturePermission: () -> Unit,
    requestCreateBackup: () -> Unit,
    requestRestoreBackup: () -> Unit,
    requestExportCsv: () -> Unit,
    requestExportHealthPdf: () -> Unit,
    shareHealthPdf: (String) -> Unit,
    installApk: (String) -> Unit,
    inPictureInPicture: Boolean = false,
    enterPictureInPicture: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDashboard by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showLastSnapshot by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var locationPermissionRequestedForConnection by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var showMileageOnlyConfirmation by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showBicycleConfirmation by remember { androidx.compose.runtime.mutableStateOf(false) }
    var mileageOnlyPermissionPending by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var bicyclePermissionPending by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showAppVersion by remember { androidx.compose.runtime.mutableStateOf(false) }
    var previewMode by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var previewScenarioOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var showDataManagement by remember { androidx.compose.runtime.mutableStateOf(false) }
    val previewScenario = DemoPreviewScenario.entries[
        previewScenarioOrdinal % DemoPreviewScenario.entries.size
    ]
    val debugHistoryState = remember { if (BuildConfig.SHOW_DEBUG_TOOLS) demoBmsState() else null }
    val debugHistorySnapshot = remember(debugHistoryState) {
        debugHistoryState?.let(::demoLastSnapshot)
    }
    val historySnapshot = state.lastSnapshot ?: debugHistorySnapshot
    val usingDebugHistory = state.lastSnapshot == null && debugHistorySnapshot != null

    val dashboardState = if (previewMode) {
        demoBmsState(appUpdate = state.appUpdate, scenario = previewScenario).copy(
            fullScreenTemperatureAlertGranted = state.fullScreenTemperatureAlertGranted,
            overlayTemperatureAlertGranted = state.overlayTemperatureAlertGranted
        )
    } else {
        state
    }

    if (inPictureInPicture) {
        PipScreen(if (previewMode) dashboardState else state)
        return
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.appUpdate.statusMessage) {
        state.appUpdate.statusMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissAppUpdateStatus()
        }
    }

    LaunchedEffect(state.dataManagement.statusMessage) {
        state.dataManagement.statusMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissDataManagementStatus()
        }
    }

    LaunchedEffect(state.appUpdate.installRequestId, state.appUpdate.apkFilePath) {
        val path = state.appUpdate.apkFilePath ?: return@LaunchedEffect
        if (state.appUpdate.installRequestId > 0) installApk(path)
    }

    LaunchedEffect(state.phase) {
        if (previewMode) return@LaunchedEffect
        when (state.phase) {
            ConnectionPhase.Ready -> {
                showLastSnapshot = false
                showDashboard = true
            }
            ConnectionPhase.Idle, ConnectionPhase.Error -> showDashboard = false
            else -> Unit
        }
    }

    LaunchedEffect(state.phase, state.basicInfo, state.locationPermissionGranted) {
        if (
            state.phase == ConnectionPhase.Ready && state.basicInfo != null &&
            !state.locationPermissionGranted && !locationPermissionRequestedForConnection
        ) {
            locationPermissionRequestedForConnection = true
            requestLocationPermission()
        }
        if (state.phase == ConnectionPhase.Idle) locationPermissionRequestedForConnection = false
    }

    LaunchedEffect(state.locationPermissionGranted, mileageOnlyPermissionPending) {
        if (state.locationPermissionGranted && mileageOnlyPermissionPending) {
            mileageOnlyPermissionPending = false
            previewMode = false
            showDashboard = false
            viewModel.startMileageOnlyTrip()
        }
    }

    LaunchedEffect(state.locationPermissionGranted, bicyclePermissionPending) {
        if (state.locationPermissionGranted && bicyclePermissionPending) {
            bicyclePermissionPending = false
            previewMode = false
            showDashboard = false
            viewModel.startBicycleTrip()
        }
    }

    val requestMileageOnlyTrip: () -> Unit = {
        if (state.locationPermissionGranted) {
            previewMode = false
            showDashboard = false
            viewModel.startMileageOnlyTrip()
        } else {
            mileageOnlyPermissionPending = true
            requestLocationPermission()
        }
    }
    val requestBicycleTrip: () -> Unit = {
        if (state.locationPermissionGranted) {
            previewMode = false
            showDashboard = false
            viewModel.startBicycleTrip()
        } else {
            bicyclePermissionPending = true
            requestLocationPermission()
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
            val lastSnapshot = historySnapshot
            if (state.trip.isTracking && state.trip.isMileageOnly) {
                MileageOnlyTripScreen(
                    state = state,
                    onFinish = viewModel::finishMileageOnlyTrip,
                    onResetSegment = viewModel::resetMileageOnlyTrip,
                    onSetCountdownTarget = viewModel::setMileageCountdownTarget,
                    onAcknowledgeCountdown = viewModel::acknowledgeMileageCountdown,
                    onEnterPictureInPicture = enterPictureInPicture
                )
            } else if (state.trip.isTracking && state.trip.isBicycle) {
                BicycleTripScreen(
                    state = state,
                    onFinish = viewModel::finishBicycleTrip,
                    onSetBodyWeight = viewModel::setBicycleBodyWeight,
                    onEnterPictureInPicture = enterPictureInPicture
                )
            } else if (showLastSnapshot && lastSnapshot != null) {
                LastSnapshotScreen(
                    snapshot = lastSnapshot,
                    capacityHealthRecords = if (usingDebugHistory) {
                        debugHistoryState?.capacityHealthRecords.orEmpty()
                    } else {
                        state.capacityHealthRecords
                    },
                    protectionEvents = if (usingDebugHistory) {
                        debugHistoryState?.protectionEvents.orEmpty()
                    } else {
                        state.protectionEvents
                    },
                    batteryTrend = if (usingDebugHistory) {
                        debugHistoryState?.batteryTrend ?: state.batteryTrend
                    } else {
                        state.batteryTrend
                    },
                    onLoadBatteryTrend = if (usingDebugHistory) ({}) else viewModel::loadBatteryTrend,
                    onAddCapacityHealthRecord = viewModel::addCapacityHealthRecord,
                    onDeleteCapacityHealthRecord = viewModel::deleteCapacityHealthRecord,
                    onStartAutomaticCapacityTest = viewModel::startAutomaticCapacityTest,
                    onFinishAutomaticCapacityTest = viewModel::finishAutomaticCapacityTest,
                    onDiscardAutomaticCapacityTest = viewModel::discardAutomaticCapacityTest,
                    onSaveAutomaticCapacityTestResult = viewModel::saveAutomaticCapacityTestResult,
                    onShowDataManagement = { showDataManagement = true },
                    onBack = { showLastSnapshot = false }
                )
            } else if ((showDashboard && state.phase == ConnectionPhase.Ready) || previewMode) {
                Dashboard(
                    state = dashboardState,
                    onShowDevices = {
                        if (previewMode) {
                            previewMode = false
                        } else {
                            viewModel.saveLastSnapshot()
                            showDashboard = false
                        }
                    },
                    onDisconnect = {
                        if (previewMode) {
                            previewMode = false
                            showDashboard = false
                        } else {
                            showDashboard = false
                            viewModel.disconnect()
                        }
                    },
                    onStartMileageOnlyTrip = { showMileageOnlyConfirmation = true },
                    onSubmitPassword = viewModel::submitBluetoothPassword,
                    onRequestLocationPermission = requestLocationPermission,
                    onRequestExit = exitApp,
                    onClearSpeedRangeStats = viewModel::clearSpeedRangeStats,
                    onAddCapacityHealthRecord = viewModel::addCapacityHealthRecord,
                    onDeleteCapacityHealthRecord = viewModel::deleteCapacityHealthRecord,
                    onStartAutomaticCapacityTest = viewModel::startAutomaticCapacityTest,
                    onFinishAutomaticCapacityTest = viewModel::finishAutomaticCapacityTest,
                    onDiscardAutomaticCapacityTest = viewModel::discardAutomaticCapacityTest,
                    onSaveAutomaticCapacityTestResult = viewModel::saveAutomaticCapacityTestResult,
                    onLoadBatteryTrend = if (previewMode) ({}) else viewModel::loadBatteryTrend,
                    onShowDataManagement = { showDataManagement = true },
                    onShowAppVersion = { showAppVersion = true },
                    onRequestFullScreenTemperaturePermission = requestFullScreenTemperaturePermission,
                    onRequestOverlayTemperaturePermission = requestOverlayTemperaturePermission,
                    onTestCriticalTemperatureAlert = viewModel::testCriticalTemperatureAlert,
                    onRefreshProtectionParams = viewModel::refreshProtectionParams,
                    onWriteAdminParameters = viewModel::writeAdminParameters,
                    onClearAdminWriteResult = viewModel::clearAdminWriteMessage,
                    onEnterPictureInPicture = enterPictureInPicture,
                    isPreview = previewMode,
                    onCyclePreviewScenario = {
                        previewScenarioOrdinal = (previewScenarioOrdinal + 1) % DemoPreviewScenario.entries.size
                    }
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
                AppHeader(
                    state = state,
                    onShowLastSnapshot = if (historySnapshot != null) {
                        { showLastSnapshot = true }
                    } else {
                        null
                    },
                    onRequestExit = exitApp,
                    onShowAppVersion = { showAppVersion = true },
                    onShowDataManagement = { showDataManagement = true },
                    isPreview = previewMode
                )
                ScanPanel(
                    state = state,
                    connect = viewModel::connect,
                    disconnect = viewModel::disconnect,
                    refreshNearby = refreshNearby,
                    startMileageOnlyTrip = requestMileageOnlyTrip,
                    startBicycleTrip = { showBicycleConfirmation = true },
                    showDashboard = { showDashboard = true },
                    showPreview = {
                        previewScenarioOrdinal = 0
                        previewMode = true
                        showDashboard = true
                    }
                )
            }
        }
    }
    state.dataManagement.healthPdfPreviewPath?.let { path ->
        BatteryHealthPdfPreviewScreen(
            filePath = path,
            onClose = viewModel::closeBatteryHealthPdfPreview,
            onSave = requestExportHealthPdf,
            onShare = shareHealthPdf
        )
    }
    if (showAppVersion) {
        AppVersionDialog(
            state = state.appUpdate,
            onDismiss = { if (!state.appUpdate.downloading) showAppVersion = false },
            onUpdate = viewModel::startAppUpdateDownload,
            onCheck = { viewModel.checkForAppUpdate(silent = false, allowPrompt = false) }
        )
    }
    if (showDataManagement) {
        DataManagementDialog(
            state = state.dataManagement,
            onDismiss = { if (!state.dataManagement.working) showDataManagement = false },
            onCreateBackup = {
                showDataManagement = false
                requestCreateBackup()
            },
            onSelectRestore = {
                showDataManagement = false
                requestRestoreBackup()
            },
            onExportCsv = {
                showDataManagement = false
                requestExportCsv()
            },
            onPreviewHealthPdf = {
                showDataManagement = false
                viewModel.previewBatteryHealthPdf(if (previewMode) dashboardState else null)
            },
            onExportHealthPdf = {
                showDataManagement = false
                requestExportHealthPdf()
            }
        )
    }
    state.dataManagement.pendingRestore?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDataRestore,
            title = { Text("确认恢复备份？") },
            text = {
                Text(
                    "备份版本：${preview.sourceVersionName}\n" +
                        "趋势明细：${preview.trendSampleCount}条\n" +
                        "每日摘要：${preview.dailySummaryCount}条\n" +
                        "满充记录：${preview.fullChargeFingerprintCount}次\n" +
                        "满充压差：${preview.fullChargeDeltaCount}次\n\n" +
                        "恢复会覆盖当前本地数据，且只能在蓝牙断开时执行。"
                )
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDataRestore) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDataRestore) {
                    Text("确认恢复", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
    if (state.appUpdate.showPrompt && state.appUpdate.available != null && !showAppVersion) {
        AppUpdateDialog(
            state = state.appUpdate,
            onDismiss = viewModel::dismissAppUpdate,
            onSkip = viewModel::skipAppUpdate,
            onUpdate = viewModel::startAppUpdateDownload
        )
    }
    if (showMileageOnlyConfirmation) {
        AlertDialog(
            onDismissRequest = { showMileageOnlyConfirmation = false },
            title = { Text("切换为仅 GPS 行程？") },
            text = {
                Text("当前 BMS 行程会先保存，随后断开蓝牙并继续累计 GPS 里程。新里程不会参与耗电、续航、容量或健康度计算。")
            },
            dismissButton = {
                TextButton(onClick = { showMileageOnlyConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMileageOnlyConfirmation = false
                        requestMileageOnlyTrip()
                    }
                ) { Text("开始 GPS 行程", color = MaterialTheme.colorScheme.primary) }
            }
        )
    }
    if (showBicycleConfirmation) {
        AlertDialog(
            onDismissRequest = { showBicycleConfirmation = false },
            title = { Text("开始自行车骑行？") },
            text = { Text("自行车里程会与电动车分开保存；热量根据体重、实时速度和有效骑行时间估算。") },
            dismissButton = { TextButton(onClick = { showBicycleConfirmation = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { showBicycleConfirmation = false; requestBicycleTrip() }) {
                    Text("开始骑行", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
    state.temperatureSafetyAlert
        ?.takeUnless { state.temperatureAlertUsesExternalSurface }
        ?.let { alert ->
        AlertDialog(
            onDismissRequest = {
                if (alert.level == TemperatureAlertLevel.Warning) {
                    viewModel.dismissTemperatureSafetyAlert()
                }
            },
            title = {
                Text(
                    alert.title,
                    color = if (alert.level == TemperatureAlertLevel.Critical) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            },
            text = {
                Text(
                    alert.message
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTemperatureSafetyAlert) {
                    Text("我已知晓")
                }
            }
        )
    }
}
