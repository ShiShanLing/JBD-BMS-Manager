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
    var showExitConfirmation by remember { androidx.compose.runtime.mutableStateOf(false) }
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
            if (showLastSnapshot && lastSnapshot != null) {
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
                    onSubmitPassword = viewModel::submitBluetoothPassword,
                    onRequestLocationPermission = requestLocationPermission,
                    onRequestExit = { showExitConfirmation = true },
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
                    onRequestExit = { showExitConfirmation = true },
                    onShowAppVersion = { showAppVersion = true },
                    onShowDataManagement = { showDataManagement = true },
                    isPreview = previewMode
                )
                ScanPanel(
                    state = state,
                    connect = viewModel::connect,
                    disconnect = viewModel::disconnect,
                    refreshNearby = refreshNearby,
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
