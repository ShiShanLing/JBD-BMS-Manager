package com.bms.jbdmanager

import android.Manifest
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.ui.BmsApp
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: BmsViewModel by viewModels()
    private var inPictureInPicture by mutableStateOf(false)
    private var minimizeOnPipClose = false
    private var allowActivityDestroy = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.setPermissionsGranted(hasBluetoothPermissions()) }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshBluetoothState() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.setLocationPermissionGranted(hasPreciseLocationPermission()) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var pendingInstallAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EXIT_ALL, false)) {
            viewModel.shutdownAll()
            allowActivityDestroy = true
            finishAndRemoveTask()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        inPictureInPicture = isInPictureInPictureMode
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
        viewModel.setLocationPermissionGranted(hasPreciseLocationPermission())
        observePictureInPictureEligibility()
        setContent {
            JbdBmsTheme {
                BmsApp(
                    viewModel = viewModel,
                    inPictureInPicture = inPictureInPicture,
                    enterPictureInPicture = ::enterPictureInPictureFromUi,
                    exitApp = {
                        viewModel.shutdownAll()
                        allowActivityDestroy = true
                        finishAndRemoveTask()
                    },
                    requestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    },
                    requestEnableBluetooth = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    requestLocationPermission = {
                        locationPermissionLauncher.launch(
                            buildList {
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }.toTypedArray()
                        )
                    },
                    installApk = ::installApk
                )
            }
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            hasPreciseLocationPermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
        viewModel.setLocationPermissionGranted(hasPreciseLocationPermission())
        if (pendingInstallAfterPermission && packageManager.canRequestPackageInstalls()) {
            pendingInstallAfterPermission = false
            viewModel.retryAppUpdateInstall()
        }
    }

    override fun onStop() {
        viewModel.saveLastSnapshot()
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            minimizeOnPipClose = true
            return
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            minimizeOnPipClose = false
        }
        val state = viewModel.uiState.value
        updatePictureInPictureParams(
            enabled = state.phase == ConnectionPhase.Ready && state.basicInfo != null
        )
    }

    override fun finish() {
        if (!allowActivityDestroy && minimizeOnPipClose) {
            minimizeOnPipClose = false
            inPictureInPicture = false
            moveTaskToBack(true)
            return
        }
        super.finish()
    }

    private fun observePictureInPictureEligibility() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updatePictureInPictureParams(
                        enabled = state.phase == ConnectionPhase.Ready && state.basicInfo != null
                    )
                }
            }
        }
    }

    private fun enterPictureInPictureFromUi() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            enterPictureInPictureMode(pictureInPictureParams(autoEnter = true))
        } catch (_: IllegalStateException) {
        }
    }

    private fun updatePictureInPictureParams(enabled: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            setPictureInPictureParams(pictureInPictureParams(autoEnter = enabled))
        } catch (_: IllegalStateException) {
            // Activity is finishing or not in a state that can enter PiP.
        }
    }

    private fun pictureInPictureParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setAutoEnterEnabled(autoEnter)
            .build()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXIT_ALL, false)) {
            viewModel.shutdownAll()
            allowActivityDestroy = true
            finishAndRemoveTask()
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun installApk(path: String) {
        val apk = File(path).takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        if (packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            pendingInstallAfterPermission = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    companion object {
        const val EXTRA_EXIT_ALL = "com.bms.jbdmanager.EXIT_ALL"
    }
}
