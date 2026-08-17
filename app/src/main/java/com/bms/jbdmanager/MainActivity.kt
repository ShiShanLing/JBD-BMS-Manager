package com.bms.jbdmanager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
        setContent {
            JbdBmsTheme {
                BmsApp(
                    viewModel = viewModel,
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
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
