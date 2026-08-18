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
import com.bms.jbdmanager.protocol.JbdAuthFrame
import com.bms.jbdmanager.protocol.JbdAuthProtocol
import com.bms.jbdmanager.protocol.JbdProtocol
import java.util.ArrayDeque
import java.util.UUID

interface JbdBleListener {
    fun onBluetoothState(supported: Boolean, enabled: Boolean)
    fun onScanStarted()
    fun onScanResult(devices: List<ScanDevice>)
    fun onScanStopped()
    fun onConnecting(address: String, name: String)
    fun onDiscovering()
    fun onReady(profile: String)
    fun onConnectionDiagnostic(message: String)
    fun onDisconnected(reason: String?)
    fun onPacketSent(packet: ByteArray, note: String)
    fun onNotification(bytes: ByteArray)
    fun onCommandTimeout(command: Int, note: String)
    fun onAuthenticationRequired(message: String)
    fun onAuthenticationSucceeded(profile: String)
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
    private var setupFinished = false
    private var pendingDisconnectReason: String? = null
    private val commandQueue = ArrayDeque<PendingCommand>()
    private var inFlight: PendingCommand? = null
    private var waitingForWriteCallback = false
    private var deferredClassicResponse: Pair<Int, Int>? = null
    private var deferredAuthFrame: JbdAuthFrame? = null
    private var authenticationBlocked = false
    private var modernProbeAttempted = false
    private var modernAuthState = ModernAuthState.Idle
    private var modernPassword: String? = null

    private enum class ModernAuthState {
        Idle,
        WaitingForPassword,
        RequestingUserRandom,
        SendingUserPassword,
        RequestingRootRandom,
        SendingRootPassword,
        Authenticated
    }

    private data class PendingCommand(
        val command: Int,
        val payload: ByteArray,
        val note: String,
        val retriesRemaining: Int = 1,
        val authFrame: Boolean = false
    )

    private val stopScanRunnable = Runnable { stopScan() }
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            enqueueRead(JbdProtocol.BASIC_INFO, "读取基本状态")
            enqueueRead(JbdProtocol.CELL_VOLTAGES, "读取单体电压")
            handler.postDelayed(this, 1_000)
        }
    }

    private val setupTimeoutRunnable = Runnable {
        failAndDisconnect("蓝牙连接或服务识别超时")
    }

    private val writeTimeoutRunnable = Runnable {
        retryOrComplete("蓝牙写入超时")
    }

    private val responseTimeoutRunnable = Runnable {
        retryOrComplete("BMS 响应超时")
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
                if (this@JbdBleManager.gatt !== gatt) {
                    gatt.close()
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener.onConnectionDiagnostic("GATT 连接成功，状态码 $status，开始识别服务")
                        listener.onDiscovering()
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        handler.removeCallbacks(setupTimeoutRunnable)
                        handler.postDelayed(setupTimeoutRunnable, SETUP_TIMEOUT_MS)
                        if (!gatt.requestMtu(247)) discoverServices(gatt)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        clearCommunicationState()
                        handler.removeCallbacks(setupTimeoutRunnable)
                        gatt.close()
                        if (this@JbdBleManager.gatt === gatt) this@JbdBleManager.gatt = null
                        val reason = pendingDisconnectReason
                            ?: if (status == BluetoothGatt.GATT_SUCCESS) null else "连接状态码 $status"
                        pendingDisconnectReason = null
                        listener.onDisconnected(reason)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                listener.onConnectionDiagnostic(
                    if (status == BluetoothGatt.GATT_SUCCESS) "MTU 协商成功：$mtu"
                    else "MTU 协商未生效，状态码 $status，继续使用默认值"
                )
                discoverServices(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failAndDisconnect("服务发现失败，状态码 $status")
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
                else failAndDisconnect("开启蓝牙通知失败，状态码 $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (!waitingForWriteCallback || inFlight == null || characteristic.uuid != writeCharacteristic?.uuid) return@post
                handler.removeCallbacks(writeTimeoutRunnable)
                waitingForWriteCallback = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retryOrComplete("蓝牙写入失败，状态码 $status")
                } else {
                    val classic = deferredClassicResponse
                    val auth = deferredAuthFrame
                    deferredClassicResponse = null
                    deferredAuthFrame = null
                    when {
                        classic != null -> handleClassicResponse(classic.first, classic.second)
                        auth != null -> handleAuthFrame(auth)
                        else -> handler.postDelayed(responseTimeoutRunnable, RESPONSE_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    fun refreshBluetoothState() {
        listener.onBluetoothState(adapter != null, adapter?.isEnabled == true)
    }

    fun startScan(keepConnection: Boolean = false) {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            listener.onBluetoothState(supported = false, enabled = false)
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener.onBluetoothState(supported = true, enabled = false)
            return
        }
        if (!keepConnection) closeGatt()
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
        pendingDisconnectReason = null
        listener.onConnecting(address, device.safeName())
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(setupTimeoutRunnable, CONNECTION_TIMEOUT_MS)
    }

    fun disconnect() {
        clearCommunicationState()
        val current = gatt ?: run {
            val reason = pendingDisconnectReason
            pendingDisconnectReason = null
            listener.onDisconnected(reason)
            return
        }
        current.disconnect()
    }

    fun disconnectForCommunicationLoss(reason: String) {
        pendingDisconnectReason = reason
        disconnect()
    }

    fun close() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        clearCommunicationState()
        closeGatt()
    }

    private fun closeGatt() {
        gatt?.runCatching { disconnect() }
        gatt?.close()
        gatt = null
        clearCommunicationState()
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

        val standardService = gatt.getService(JBD_STANDARD_SERVICE_UUID)
        val standardWriter = standardService?.getCharacteristic(JBD_STANDARD_WRITE_UUID)?.takeIf { it in writable }
        val standardNotifier = standardService?.getCharacteristic(JBD_STANDARD_NOTIFY_UUID)?.takeIf { it in notifiable }

        val compatibleService = gatt.getService(JBD_COMPATIBLE_SERVICE_UUID)
        val compatibleChannel = compatibleService?.getCharacteristic(JBD_COMPATIBLE_CHANNEL_UUID)
            ?.takeIf { it in writable && it in notifiable }

        if (standardWriter != null && standardNotifier != null) {
            writeCharacteristic = standardWriter
            notifyCharacteristic = standardNotifier
        } else if (compatibleChannel != null) {
            writeCharacteristic = compatibleChannel
            notifyCharacteristic = compatibleChannel
        } else {
            writeCharacteristic = writable.maxByOrNull(::writePriority)
            notifyCharacteristic = notifiable.maxByOrNull(::notifyPriority)
                ?: writeCharacteristic?.takeIf { it in notifiable }
        }

        val writer = writeCharacteristic
        val notifier = notifyCharacteristic
        if (writer == null || notifier == null) {
            val services = gatt.services.joinToString { it.uuid.shortLabel() }
            failAndDisconnect("没有找到可用的写入/通知特征。服务：$services")
            return
        }

        if (!gatt.setCharacteristicNotification(notifier, true)) {
            failAndDisconnect("无法启用蓝牙通知")
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
        if (!started) failAndDisconnect("写入通知配置失败")
    }

    private fun finishSetup() {
        if (setupFinished) return
        val writer = writeCharacteristic ?: return
        val notifier = notifyCharacteristic ?: return
        setupFinished = true
        handler.removeCallbacks(setupTimeoutRunnable)
        val profile = when {
            writer.uuid.isShort("ff02") && notifier.uuid.isShort("ff01") -> "标准 JBD BLE（FF00/FF01/FF02）"
            writer.uuid.isShort("ffe1") && notifier.uuid.isShort("ffe1") -> "JBD 兼容 BLE（FFE0/FFE1）"
            else -> "通用 BLE 自动探测"
        }
        val services = gatt?.services.orEmpty().joinToString(", ") { it.uuid.shortLabel() }
        listener.onConnectionDiagnostic(
            "BLE通道：$profile；服务：$services；写入特征：${writer.uuid.shortLabel()}；通知特征：${notifier.uuid.shortLabel()}"
        )
        listener.onReady(profile)
        enqueueRead(JbdProtocol.BASIC_INFO, "读取基本状态")
        enqueueRead(JbdProtocol.CELL_VOLTAGES, "读取单体电压")
        enqueueRead(JbdProtocol.HARDWARE_VERSION, "识别硬件型号")
        enqueueRead(JbdProtocol.CHIP_TYPE, "识别芯片方案")
        polling = true
        handler.postDelayed(pollRunnable, 1_000)
    }

    fun onProtocolResponse(command: Int, status: Int) {
        handler.post {
            val current = inFlight ?: return@post
            if (current.authFrame || current.command != command) return@post
            handler.removeCallbacks(responseTimeoutRunnable)
            if (waitingForWriteCallback) deferredClassicResponse = command to status
            else handleClassicResponse(command, status)
        }
    }

    fun sendAuthenticationPassword(password: String): Boolean {
        val payload = JbdProtocol.passwordPairCommand(password).getOrNull() ?: return false
        handler.post {
            authenticationBlocked = false
            if (modernAuthState == ModernAuthState.WaitingForPassword) {
                modernPassword = password
                modernAuthState = ModernAuthState.RequestingUserRandom
                enqueueAuth(JbdAuthProtocol.GET_RANDOM, JbdAuthProtocol.randomRequest(), "新版蓝牙认证：获取随机码")
            } else {
                enqueueCommand(PendingCommand(JbdProtocol.PASSWORD_PAIRING, payload, "只读蓝牙身份认证", 0), first = true)
            }
        }
        return true
    }

    private fun enqueueRead(command: Int, note: String) {
        enqueueCommand(PendingCommand(command, JbdProtocol.readCommand(command), note))
    }

    private fun enqueueCommand(command: PendingCommand, first: Boolean = false) {
        if (!setupFinished) return
        if (authenticationBlocked && command.command != JbdProtocol.PASSWORD_PAIRING && !command.authFrame) return
        if (inFlight?.command == command.command || commandQueue.any { it.command == command.command }) return
        if (first) commandQueue.addFirst(command) else commandQueue.addLast(command)
        sendNextCommand()
    }

    private fun sendNextCommand() {
        if (inFlight != null || commandQueue.isEmpty()) return
        val currentGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val command = commandQueue.removeFirst()
        inFlight = command
        deferredClassicResponse = null
        deferredAuthFrame = null
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, command.payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = command.payload
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        if (started) {
            listener.onPacketSent(command.payload, command.note)
            waitingForWriteCallback = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            handler.postDelayed(
                if (waitingForWriteCallback) writeTimeoutRunnable else responseTimeoutRunnable,
                if (waitingForWriteCallback) WRITE_TIMEOUT_MS else RESPONSE_TIMEOUT_MS
            )
        } else {
            waitingForWriteCallback = false
            retryOrComplete("发送失败：${command.note}")
        }
    }

    private fun retryOrComplete(reason: String) {
        handler.removeCallbacks(writeTimeoutRunnable)
        handler.removeCallbacks(responseTimeoutRunnable)
        val current = inFlight ?: return
        inFlight = null
        waitingForWriteCallback = false
        deferredClassicResponse = null
        deferredAuthFrame = null
        if (current.retriesRemaining > 0 && setupFinished) {
            commandQueue.addFirst(current.copy(retriesRemaining = current.retriesRemaining - 1))
            handler.postDelayed(::sendNextCommand, RETRY_DELAY_MS)
        } else {
            listener.onCommandTimeout(current.command, "${current.note}：$reason")
            if (!current.authFrame && current.command == JbdProtocol.BASIC_INFO && !modernProbeAttempted) {
                modernProbeAttempted = true
                enqueueCommand(
                    PendingCommand(
                        JbdAuthProtocol.SEND_APP_KEY,
                        JbdAuthProtocol.appKey(),
                        "探测 JBD 新版蓝牙认证协议",
                        retriesRemaining = 0,
                        authFrame = true
                    ),
                    first = true
                )
            }
            sendNextCommand()
        }
    }

    private fun completeCurrentCommand(startNext: Boolean = true) {
        handler.removeCallbacks(writeTimeoutRunnable)
        handler.removeCallbacks(responseTimeoutRunnable)
        inFlight = null
        waitingForWriteCallback = false
        deferredClassicResponse = null
        deferredAuthFrame = null
        if (startNext) sendNextCommand()
    }

    private fun onBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        handler.post {
            listener.onNotification(bytes.copyOf())
            if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xAA) {
                JbdAuthProtocol.decode(bytes).onSuccess { frame ->
                    val current = inFlight
                    if (current?.authFrame == true && current.command == frame.command) {
                        handler.removeCallbacks(responseTimeoutRunnable)
                        if (waitingForWriteCallback) deferredAuthFrame = frame else handleAuthFrame(frame)
                    }
                }.onFailure { listener.onError("认证报文无效：${it.message}") }
            }
        }
    }

    private fun handleClassicResponse(command: Int, status: Int) {
        if (status == 0x83) {
            authenticationBlocked = true
            commandQueue.clear()
            completeCurrentCommand(startNext = false)
            listener.onAuthenticationRequired("此蓝牙模块需要6位读取密码")
            return
        }
        if (command == JbdProtocol.PASSWORD_PAIRING) {
            completeCurrentCommand(startNext = false)
            if (status == 0) {
                authenticationBlocked = false
                listener.onAuthenticationSucceeded("JBD 经典密码认证")
                resumeStandardReads()
            } else {
                authenticationBlocked = true
                listener.onAuthenticationRequired("蓝牙密码认证失败（状态码 0x${status.toString(16).uppercase()}）")
            }
            return
        }
        completeCurrentCommand()
    }

    private fun handleAuthFrame(frame: JbdAuthFrame) {
        val result = frame.data.firstOrNull()?.toInt()?.and(0xFF)
        completeCurrentCommand(startNext = false)
        when (frame.command) {
            JbdAuthProtocol.SEND_APP_KEY -> when (result) {
                0x02 -> {
                    modernAuthState = ModernAuthState.Authenticated
                    authenticationBlocked = false
                    listener.onAuthenticationSucceeded("JBD 新版 FF AA 认证")
                    resumeStandardReads()
                }
                0x00 -> {
                    modernAuthState = ModernAuthState.WaitingForPassword
                    authenticationBlocked = true
                    commandQueue.clear()
                    listener.onAuthenticationRequired("检测到 JBD 新版认证模块，请输入6位用户密码")
                }
                else -> listener.onError("新版蓝牙认证握手失败")
            }
            JbdAuthProtocol.GET_RANDOM -> {
                val random = result ?: run {
                    listener.onError("新版蓝牙认证没有返回随机码")
                    return
                }
                when (modernAuthState) {
                    ModernAuthState.RequestingUserRandom -> {
                        val password = modernPassword ?: return
                        val payload = JbdAuthProtocol.userPassword(password, gatt?.device?.address.orEmpty(), random)
                            .getOrElse {
                                listener.onError(it.message ?: "无法生成用户认证报文")
                                return
                            }
                        modernAuthState = ModernAuthState.SendingUserPassword
                        enqueueAuth(JbdAuthProtocol.SEND_PASSWORD, payload, "新版蓝牙认证：验证用户密码")
                    }
                    ModernAuthState.RequestingRootRandom -> {
                        val payload = JbdAuthProtocol.rootPassword(gatt?.device?.address.orEmpty(), random)
                            .getOrElse {
                                listener.onError(it.message ?: "无法生成读取授权报文")
                                return
                            }
                        modernAuthState = ModernAuthState.SendingRootPassword
                        enqueueAuth(JbdAuthProtocol.SEND_ROOT_PASSWORD, payload, "新版蓝牙认证：取得读取授权")
                    }
                    else -> Unit
                }
            }
            JbdAuthProtocol.SEND_PASSWORD -> if (result == 0) {
                modernAuthState = ModernAuthState.RequestingRootRandom
                enqueueAuth(JbdAuthProtocol.GET_RANDOM, JbdAuthProtocol.randomRequest(), "新版蓝牙认证：获取授权随机码")
            } else {
                modernAuthState = ModernAuthState.WaitingForPassword
                authenticationBlocked = true
                listener.onAuthenticationRequired("新版蓝牙用户密码不正确")
            }
            JbdAuthProtocol.SEND_ROOT_PASSWORD -> if (result == 0) {
                modernAuthState = ModernAuthState.Authenticated
                authenticationBlocked = false
                listener.onAuthenticationSucceeded("JBD 新版 FF AA 认证")
                resumeStandardReads()
            } else {
                authenticationBlocked = true
                listener.onError("新版蓝牙读取授权失败")
            }
        }
    }

    private fun enqueueAuth(command: Int, payload: ByteArray, note: String) {
        enqueueCommand(PendingCommand(command, payload, note, retriesRemaining = 0, authFrame = true), first = true)
    }

    private fun resumeStandardReads() {
        enqueueRead(JbdProtocol.BASIC_INFO, "认证后读取基本状态")
        enqueueRead(JbdProtocol.CELL_VOLTAGES, "认证后读取单体电压")
        enqueueRead(JbdProtocol.HARDWARE_VERSION, "认证后识别硬件型号")
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        if (this.gatt !== gatt || setupFinished) return
        if (!gatt.discoverServices()) failAndDisconnect("无法启动蓝牙服务识别")
    }

    private fun failAndDisconnect(reason: String) {
        if (gatt == null) {
            listener.onError(reason)
            return
        }
        pendingDisconnectReason = reason
        clearCommunicationState()
        gatt?.disconnect()
    }

    private fun clearCommunicationState() {
        polling = false
        setupFinished = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(setupTimeoutRunnable)
        handler.removeCallbacks(writeTimeoutRunnable)
        handler.removeCallbacks(responseTimeoutRunnable)
        commandQueue.clear()
        inFlight = null
        waitingForWriteCallback = false
        deferredClassicResponse = null
        deferredAuthFrame = null
        authenticationBlocked = false
        modernProbeAttempted = false
        modernAuthState = ModernAuthState.Idle
        modernPassword = null
        writeCharacteristic = null
        notifyCharacteristic = null
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
        private val JBD_STANDARD_SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val JBD_STANDARD_NOTIFY_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val JBD_STANDARD_WRITE_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val JBD_COMPATIBLE_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val JBD_COMPATIBLE_CHANNEL_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val SETUP_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val RESPONSE_TIMEOUT_MS = 2_200L
        private const val RETRY_DELAY_MS = 180L
    }
}
