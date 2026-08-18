package com.bms.jbdmanager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.bms.jbdmanager.ui.BmsApp
import com.bms.jbdmanager.ui.theme.JbdBmsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: BmsViewModel by viewModels()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EXIT_ALL, false)) {
            viewModel.shutdownAll()
            finishAndRemoveTask()
            return
        }
        enableEdgeToEdge()
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
        viewModel.setLocationPermissionGranted(hasPreciseLocationPermission())
        setContent {
            JbdBmsTheme {
                BmsApp(
                    viewModel = viewModel,
                    exitApp = {
                        viewModel.shutdownAll()
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
                    }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXIT_ALL, false)) {
            viewModel.shutdownAll()
            finishAndRemoveTask()
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_EXIT_ALL = "com.bms.jbdmanager.EXIT_ALL"
    }
}
