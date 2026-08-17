package com.bms.jbdmanager.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bms.jbdmanager.model.ScanDevice
import com.bms.jbdmanager.protocol.JbdProtocol
import java.util.UUID

interface JbdBleListener {
    fun onBluetoothState(supported: Boolean, enabled: Boolean)
    fun onScanStarted()
    fun onScanResult(devices: List<ScanDevice>)
    fun onScanStopped()
    fun onConnecting(address: String, name: String)
    fun onDiscovering()
    fun onReady(profile: String)
    fun onDisconnected(reason: String?)
    fun onPacketSent(packet: ByteArray, note: String)
    fun onNotification(bytes: ByteArray)
    fun onError(message: String)
}

@SuppressLint("MissingPermission")
class JbdBleManager(
    context: Context,
    private val listener: JbdBleListener
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanResults = linkedMapOf<String, ScanDevice>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var polling = false

    private val stopScanRunnable = Runnable { stopScan() }
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            sendRead(JbdProtocol.BASIC_INFO, "读取基本状态")
            handler.postDelayed({
                if (polling) sendRead(JbdProtocol.CELL_VOLTAGES, "读取单体电压")
            }, 260)
            handler.postDelayed(this, 1_000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = updateScanResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::updateScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onError("蓝牙扫描失败，错误码 $errorCode")
            listener.onScanStopped()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener.onDiscovering()
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(247)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        polling = false
                        handler.removeCallbacks(pollRunnable)
                        writeCharacteristic = null
                        notifyCharacteristic = null
                        gatt.close()
                        if (this@JbdBleManager.gatt === gatt) this@JbdBleManager.gatt = null
                        listener.onDisconnected(if (status == BluetoothGatt.GATT_SUCCESS) null else "连接状态码 $status")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("服务发现失败，状态码 $status")
                    return@post
                }
                configureCharacteristics(gatt)
            }
        }

        @Deprecated("Android 13 compatibility callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onBytes(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onBytes(value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) finishSetup()
                else listener.onError("开启蓝牙通知失败，状态码 $status")
            }
        }
    }

    fun refreshBluetoothState() {
        listener.onBluetoothState(adapter != null, adapter?.isEnabled == true)
    }

    fun startScan() {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            listener.onBluetoothState(supported = false, enabled = false)
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener.onBluetoothState(supported = true, enabled = false)
            return
        }
        disconnect()
        scanResults.clear()
        listener.onScanResult(emptyList())
        listener.onScanStarted()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
            ?: listener.onError("无法启动蓝牙扫描")
        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, 12_000)
    }

    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        listener.onScanStopped()
    }

    fun connect(address: String) {
        stopScan()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            listener.onError("找不到蓝牙设备 $address")
            return
        }
        closeGatt()
        listener.onConnecting(address, device.safeName())
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        val current = gatt ?: return
        current.disconnect()
    }

    fun close() {
        stopScan()
        polling = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
    }

    private fun closeGatt() {
        gatt?.runCatching { disconnect() }
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private fun updateScanResult(result: ScanResult) {
        val device = result.device
        val name = result.scanRecord?.deviceName ?: device.safeName()
        val advertisedUuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString().lowercase() }
        val nameLower = name.lowercase()
        val likely = nameLower.contains("jbd") || nameLower.contains("xiaoxiang") ||
            nameLower.contains("bms") || advertisedUuids.any { it.contains("ff00") || it.contains("ffe0") }
        scanResults[device.address] = ScanDevice(device.address, name, result.rssi, likely)
        val sorted = scanResults.values.sortedWith(
            compareByDescending<ScanDevice> { it.looksLikeJbd }.thenByDescending { it.rssi }
        )
        listener.onScanResult(sorted)
    }

    private fun BluetoothDevice.safeName(): String =
        runCatching { name }.getOrNull().orEmpty().ifBlank { "未命名设备" }

    private fun configureCharacteristics(gatt: BluetoothGatt) {
        val all = gatt.services.flatMap(BluetoothGattService::getCharacteristics)
        val writable = all.filter { characteristic ->
            characteristic.properties and (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) != 0
        }
        val notifiable = all.filter { characteristic ->
            characteristic.properties and (
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                ) != 0
        }

        writeCharacteristic = writable.maxByOrNull(::writePriority)
        notifyCharacteristic = notifiable.maxByOrNull(::notifyPriority)
            ?: writeCharacteristic?.takeIf { it in notifiable }

        val writer = writeCharacteristic
        val notifier = notifyCharacteristic
        if (writer == null || notifier == null) {
            val services = gatt.services.joinToString { it.uuid.shortLabel() }
            listener.onError("没有找到可用的写入/通知特征。服务：$services")
            return
        }

        if (!gatt.setCharacteristicNotification(notifier, true)) {
            listener.onError("无法启用蓝牙通知")
            return
        }
        val descriptor = notifier.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            finishSetup()
            return
        }
        val value = if (notifier.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) listener.onError("写入通知配置失败")
    }

    private fun finishSetup() {
        val writer = writeCharacteristic ?: return
        val notifier = notifyCharacteristic ?: return
        val profile = when {
            writer.uuid.isShort("ff02") && notifier.uuid.isShort("ff01") -> "标准 JBD BLE（FF00/FF01/FF02）"
            writer.uuid.isShort("ffe1") && notifier.uuid.isShort("ffe1") -> "JBD 兼容 BLE（FFE0/FFE1）"
            else -> "通用 BLE 自动探测"
        }
        listener.onReady(profile)
        handler.postDelayed({ sendRead(JbdProtocol.HARDWARE_VERSION, "识别硬件型号") }, 180)
        handler.postDelayed({ sendRead(JbdProtocol.CHIP_TYPE, "识别芯片方案") }, 500)
        polling = true
        handler.postDelayed(pollRunnable, 850)
    }

    private fun sendRead(command: Int, note: String) {
        val payload = JbdProtocol.readCommand(command)
        val currentGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        if (started) listener.onPacketSent(payload, note)
        else listener.onError("发送失败：$note")
    }

    private fun onBytes(bytes: ByteArray) {
        if (bytes.isNotEmpty()) handler.post { listener.onNotification(bytes.copyOf()) }
    }

    private fun writePriority(characteristic: BluetoothGattCharacteristic): Int = when {
        characteristic.uuid.isShort("ff02") -> 100
        characteristic.uuid.isShort("ffe1") -> 90
        characteristic.service.uuid.isShort("ff00") -> 80
        characteristic.service.uuid.isShort("ffe0") -> 70
        else -> 1
    }

    private fun notifyPriority(characteristic: BluetoothGattCharacteristic): Int = when {
        characteristic.uuid.isShort("ff01") -> 100
        characteristic.uuid.isShort("ffe1") -> 90
        characteristic.service.uuid.isShort("ff00") -> 80
        characteristic.service.uuid.isShort("ffe0") -> 70
        else -> 1
    }

    private fun UUID.isShort(value: String): Boolean =
        toString().lowercase().startsWith("0000${value.lowercase()}-")

    private fun UUID.shortLabel(): String = toString().substringBefore("-0000-1000")

    companion object {
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
