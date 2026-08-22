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
import com.bms.jbdmanager.BmsViewModel
import com.bms.jbdmanager.model.ConnectionPhase

@Composable
fun BmsApp(
    viewModel: BmsViewModel,
    exitApp: () -> Unit,
    requestPermissions: () -> Unit,
    requestEnableBluetooth: () -> Unit,
    requestLocationPermission: () -> Unit,
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
    val previewScenario = DemoPreviewScenario.entries[
        previewScenarioOrdinal % DemoPreviewScenario.entries.size
    ]

    val dashboardState = if (previewMode) {
        demoBmsState(appUpdate = state.appUpdate, scenario = previewScenario)
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
            val lastSnapshot = state.lastSnapshot
            if (showLastSnapshot && lastSnapshot != null) {
                LastSnapshotScreen(
                    snapshot = lastSnapshot,
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
                    onSubmitPassword = viewModel::submitBluetoothPassword,
                    onRequestLocationPermission = requestLocationPermission,
                    onRequestExit = { showExitConfirmation = true },
                    onClearSpeedRangeStats = viewModel::clearSpeedRangeStats,
                    onShowAppVersion = { showAppVersion = true },
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
                    onShowLastSnapshot = if (state.lastSnapshot != null) {
                        { showLastSnapshot = true }
                    } else {
                        null
                    },
                    onRequestExit = { showExitConfirmation = true },
                    onShowAppVersion = { showAppVersion = true },
                    isPreview = previewMode
                )
                ScanPanel(
                    state = state,
                    connect = viewModel::connect,
                    disconnect = viewModel::disconnect,
                    refreshNearby = refreshNearby,
                    showDashboard = { showDashboard = true }
                )
            }
        }
    }
    if (showAppVersion) {
        AppVersionDialog(
            state = state.appUpdate,
            onDismiss = { if (!state.appUpdate.downloading) showAppVersion = false },
            onUpdate = viewModel::startAppUpdateDownload,
            onCheck = { viewModel.checkForAppUpdate(silent = true, allowPrompt = false) }
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
}
