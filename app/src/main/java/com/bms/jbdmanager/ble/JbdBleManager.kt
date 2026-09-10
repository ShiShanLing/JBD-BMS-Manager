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

//MARK:蓝牙事件接口
//JbdBleListener 规定JbdBleListener对外暴露的回调能力，调用方只依赖接口而不直接操作内部实现。
interface JbdBleListener {
    //MARK:蓝牙状态
    //onBluetoothState 报告手机是否支持并已开启蓝牙，使上层决定是否自动扫描或连接。
    fun onBluetoothState(supported: Boolean, enabled: Boolean)
    //MARK:扫描开始
    //onScanStarted 通知上层 BLE 扫描已经开始，用于切换扫描状态和旋转图标。
    fun onScanStarted()
    //MARK:扫描结果
    //onScanResult 把本次发现或更新后的附近设备列表交给上层展示。
    fun onScanResult(devices: List<ScanDevice>)
    //MARK:扫描停止
    //onScanStopped 通知上层扫描已停止，使页面退出扫描中状态但继续保留结果。
    fun onScanStopped()
    //MARK:正在连接
    //onConnecting 报告目标设备地址和名称，使上层进入连接中状态并保存当前目标。
    fun onConnecting(address: String, name: String)
    //MARK:发现服务
    //onDiscovering 报告 GATT 已连接且正在发现服务，使页面展示服务识别阶段。
    fun onDiscovering()
    //MARK:连接就绪
    //onReady 报告可读写 BLE 通道已配置完成，并携带最终识别出的通道类型。
    fun onReady(profile: String)
    //MARK:连接诊断
    //onConnectionDiagnostic 上报服务 UUID、写入特征和通知特征等连接诊断信息。
    fun onConnectionDiagnostic(message: String)
    //MARK:连接断开
    //onDisconnected 报告连接已经断开及可选原因，由上层区分手动断开和意外掉线。
    fun onDisconnected(reason: String?)
    //MARK:数据发送
    //onPacketSent 报告已交给系统发送的命令原始字节和用途，供调试或统计使用。
    fun onPacketSent(packet: ByteArray, note: String)
    //MARK:接收通知
    //onNotification 转交 BMS 通知特征收到的原始字节，供上层组装和解析业务报文。
    fun onNotification(bytes: ByteArray)
    //MARK:命令超时
    //onCommandTimeout 报告未在期限内完成的命令及原因，使上层标记数据等待或参数超时。
    fun onCommandTimeout(command: Int, note: String)
    //MARK:需要认证
    //onAuthenticationRequired 通知上层当前模块要求读取密码，并触发密码输入或自动重试。
    fun onAuthenticationRequired(message: String)
    //MARK:认证成功
    //onAuthenticationSucceeded 报告只读认证已经完成，并携带实际使用的认证协议名称。
    fun onAuthenticationSucceeded(profile: String)
    //MARK:错误处理
    //onError 把无法自动恢复的蓝牙或协议错误转换为用户可见提示。
    fun onError(message: String)
}

@SuppressLint("MissingPermission")
//MARK:蓝牙连接管理
//JbdBleManager 管理扫描、GATT 生命周期、JBD 特征识别、串行命令队列、轮询及两套只读认证流程。
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

    //MARK:蓝牙认证状态
    //ModernAuthState 枚举认证状态的全部合法取值；新增状态时需要同步检查解析、存储和界面分支。
    private enum class ModernAuthState {
        Idle,
        WaitingForPassword,
        RequestingUserRandom,
        SendingUserPassword,
        RequestingRootRandom,
        SendingRootPassword,
        Authenticated
    }

    //MARK:待发命令
    //PendingCommand 将命令相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    private data class PendingCommand(
        val command: Int,
        val payload: ByteArray,
        val note: String,
        val retriesRemaining: Int = 1,
        val authFrame: Boolean = false
    )

    private val stopScanRunnable = Runnable { stopScan() }
    private val pollRunnable = object : Runnable {
        //MARK:执行超时任务
        //run 执行每秒轮询任务，依次请求基本状态和单体电压后安排下一轮。
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
        //MARK:扫描结果
        //onScanResult 把本次发现或更新后的附近设备列表交给上层展示。
        override fun onScanResult(callbackType: Int, result: ScanResult) = updateScanResult(result)

        //MARK:批量结果
        //onBatchScanResults 逐个合并系统批量返回的扫描结果，并按蓝牙地址更新已有设备。
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::updateScanResult)
        }

        //MARK:扫描失败
        //onScanFailed 把 Android 扫描错误码报告给上层，并同步结束扫描状态。
        override fun onScanFailed(errorCode: Int) {
            listener.onError("蓝牙扫描失败，错误码 $errorCode")
            listener.onScanStopped()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        //MARK:连接变化
        //onConnectionStateChange 处理 GATT 连接与断开回调；忽略旧实例事件并启动 MTU、服务发现或资源清理。
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                // Android 可能在旧连接关闭后仍回调事件；只允许当前 GATT 实例驱动状态，避免旧连接覆盖新连接。
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
                        // 先协商较大的 MTU，减少一条 JBD 报文被拆成多个通知包的概率；协商失败仍可按默认 MTU 工作。
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

        //MARK:MTU变化
        //onMtuChanged 记录 MTU 协商结果后继续发现服务；协商失败不会阻断默认 MTU 通信。
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                listener.onConnectionDiagnostic(
                    if (status == BluetoothGatt.GATT_SUCCESS) "MTU 协商成功：$mtu"
                    else "MTU 协商未生效，状态码 $status，继续使用默认值"
                )
                discoverServices(gatt)
            }
        }

        //MARK:服务发现
        //onServicesDiscovered 检查服务发现状态，成功时选择 JBD 通道，失败时关闭当前连接。
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
        //MARK:特征通知
        //onCharacteristicChanged 接收通知特征的新字节，并统一交给认证帧或普通数据处理入口。
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onBytes(characteristic.value ?: byteArrayOf())
        }

        //MARK:特征通知
        //onCharacteristicChanged 接收通知特征的新字节，并统一交给认证帧或普通数据处理入口。
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onBytes(value)
        }

        //MARK:描述符写入
        //onDescriptorWrite 确认通知描述符写入结果；成功后开始首次读取，失败则断开无效通道。
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) finishSetup()
                else failAndDisconnect("开启蓝牙通知失败，状态码 $status")
            }
        }

        //MARK:特征写入
        //onCharacteristicWrite 关联当前在途命令的写入结果，并正确处理可能先于写回调到达的 BMS 响应。
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                // 写回调和 BMS 响应的先后顺序并不固定：响应先到时会暂存，写成功后再统一处理。
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

    //MARK:刷新蓝牙
    //refreshBluetoothState 要求蓝牙管理器重新报告硬件支持与开关状态，不直接开始扫描。
    fun refreshBluetoothState() {
        listener.onBluetoothState(adapter != null, adapter?.isEnabled == true)
    }

    //MARK:开始扫描
    //startScan 检查权限和当前连接状态后开始附近设备扫描，并清空已过期的临时扫描结果。
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

    //MARK:停止扫描
    //stopScan 停止正在进行的 BLE 扫描并取消扫描超时任务，保留已经发现的设备。
    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        listener.onScanStopped()
    }

    //MARK:连接设备
    //connect 根据蓝牙地址建立新连接，并在连接前重置上一会话的临时通信状态。
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

    //MARK:断开设备
    //disconnect 主动断开当前蓝牙连接并清理命令队列，使后续不会自动沿用本次会话。
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

    //MARK:通信异常断开
    //disconnectForCommunicationLoss 切换disconnectForCommunicationLoss所处的蓝牙生命周期，并同步清理超时任务和临时状态。
    fun disconnectForCommunicationLoss(reason: String) {
        pendingDisconnectReason = reason
        disconnect()
    }

    //MARK:关闭资源
    //close 切换close所处的蓝牙生命周期，并同步清理超时任务和临时状态。
    fun close() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        clearCommunicationState()
        closeGatt()
    }

    //MARK:关闭连接资源
    //closeGatt 切换closeGatt所处的蓝牙生命周期，并同步清理超时任务和临时状态。
    private fun closeGatt() {
        gatt?.runCatching { disconnect() }
        gatt?.close()
        gatt = null
        clearCommunicationState()
    }

    //MARK:更新更新
    //updateScanResult 按蓝牙地址合并扫描结果，保留可用名称和最新信号强度后刷新设备列表。
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

    //MARK:读取设备名
    //安全读取可能受系统权限限制的蓝牙名称；读取失败或空名称时统一显示“未命名设备”。
    private fun BluetoothDevice.safeName(): String =
        runCatching { name }.getOrNull().orEmpty().ifBlank { "未命名设备" }

    //MARK:选择特征
    //configureCharacteristics 优先选择 FF00 标准通道，其次 FFE1 兼容通道，最后按属性自动探测读写特征。
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

        //MARK:通道选择
        //优先使用已确认的 JBD 标准通道，其次兼容 FFE1；只有型号未知时才按特征能力和 UUID 优先级自动探测。
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
            // 少数模块没有暴露 CCCD，但本地通知开关已成功；继续初始化，让实际收包结果决定通道是否可用。
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

    //MARK:完成配置
    //finishSetup 在通知开启后完成一次性初始化，识别通道类型并加入首次读取与轮询命令。
    private fun finishSetup() {
        // 多个系统回调可能同时到达，只允许初始化一次，防止轮询任务和首次读取命令重复入队。
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
        enqueueProtectionParams()
        polling = true
        handler.postDelayed(pollRunnable, 1_000)
    }

    //MARK:协议回调
    //onProtocolResponse 把已解码的 DD/77 命令状态关联到当前在途命令，并结束对应响应等待。
    fun onProtocolResponse(command: Int, status: Int) {
        handler.post {
            val current = inFlight ?: return@post
            if (current.authFrame || current.command != command) return@post
            handler.removeCallbacks(responseTimeoutRunnable)
            if (waitingForWriteCallback) deferredClassicResponse = command to status
            else handleClassicResponse(command, status)
        }
    }

    //MARK:发送认证密码
    //sendAuthenticationPassword 推进sendAuthenticationPassword的串行命令队列，确保一次只关联一个写入和响应。
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

    //MARK:读取入队
    //enqueueRead 推进enqueueRead的串行命令队列，确保一次只关联一个写入和响应。
    private fun enqueueRead(command: Int, note: String) {
        enqueueCommand(PendingCommand(command, JbdProtocol.readCommand(command), note))
    }

    //MARK:命令入队
    //enqueueCommand 推进enqueueCommand的串行命令队列，确保一次只关联一个写入和响应。
    private fun enqueueCommand(command: PendingCommand, first: Boolean = false) {
        if (!setupFinished) return
        if (authenticationBlocked && command.command != JbdProtocol.PASSWORD_PAIRING && !command.authFrame) return
        // 同一命令只保留一个实例，避免轮询速度大于蓝牙响应速度时队列无限增长。
        if (inFlight?.command == command.command || commandQueue.any { it.command == command.command }) return
        if (first) commandQueue.addFirst(command) else commandQueue.addLast(command)
        sendNextCommand()
    }

    //MARK:发送命令
    //sendNextCommand 推进sendNextCommand的串行命令队列，确保一次只关联一个写入和响应。
    private fun sendNextCommand() {
        //MARK:串行发送
        //BLE 写入和 DD/77 响应按单通道串行关联；前一命令完成或超时前不发送下一条。
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
            // 有响应写先等系统写回调，无响应写直接等 BMS 通知；两个阶段使用不同超时，避免误判。
            handler.postDelayed(
                if (waitingForWriteCallback) writeTimeoutRunnable else responseTimeoutRunnable,
                if (waitingForWriteCallback) WRITE_TIMEOUT_MS else RESPONSE_TIMEOUT_MS
            )
        } else {
            waitingForWriteCallback = false
            retryOrComplete("发送失败：${command.note}")
        }
    }

    //MARK:失败重试
    //retryOrComplete 推进retryOrComplete的串行命令队列，确保一次只关联一个写入和响应。
    private fun retryOrComplete(reason: String) {
        handler.removeCallbacks(writeTimeoutRunnable)
        handler.removeCallbacks(responseTimeoutRunnable)
        val current = inFlight ?: return
        inFlight = null
        waitingForWriteCallback = false
        deferredClassicResponse = null
        deferredAuthFrame = null
        if (current.retriesRemaining > 0 && setupFinished) {
            // 重试插到队首，保证后续响应仍能和原命令对应，而不会被其他轮询命令穿插。
            commandQueue.addFirst(current.copy(retriesRemaining = current.retriesRemaining - 1))
            handler.postDelayed(::sendNextCommand, RETRY_DELAY_MS)
        } else {
            listener.onCommandTimeout(current.command, "${current.note}：$reason")
            if (!current.authFrame && current.command == JbdProtocol.BASIC_INFO && !modernProbeAttempted) {
                // 经典 DD/77 基本信息无响应时仅探测一次新版 FF/AA 认证，避免普通模块被反复认证打扰。
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

    //MARK:完成命令
    //completeCurrentCommand 推进当前的串行命令队列，确保一次只关联一个写入和响应。
    private fun completeCurrentCommand(startNext: Boolean = true) {
        handler.removeCallbacks(writeTimeoutRunnable)
        handler.removeCallbacks(responseTimeoutRunnable)
        inFlight = null
        waitingForWriteCallback = false
        deferredClassicResponse = null
        deferredAuthFrame = null
        if (startNext) sendNextCommand()
    }

    //MARK:处理接收字节
    //onBytes 接收并处理字节流，校验它与当前连接或在途命令一致后再推进通信状态。
    private fun onBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        handler.post {
            listener.onNotification(bytes.copyOf())
            // FF/AA 是新版认证帧；普通 DD/77 数据由上层帧组装器接收，二者不能混用校验规则。
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

    //MARK:经典响应
    //handleClassicResponse 处理经典 DD/77 状态码；识别密码要求、认证成功或失败后决定是否恢复读取。
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

    //MARK:认证响应
    //handleAuthFrame 接收并处理认证报文，校验它与当前连接或在途命令一致后再推进通信状态。
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

    //MARK:认证入队
    //enqueueAuth 推进认证的串行命令队列，确保一次只关联一个写入和响应。
    private fun enqueueAuth(command: Int, payload: ByteArray, note: String) {
        enqueueCommand(PendingCommand(command, payload, note, retriesRemaining = 0, authFrame = true), first = true)
    }

    //MARK:恢复读取
    //resumeStandardReads 推进resumeStandardReads的串行命令队列，确保一次只关联一个写入和响应。
    private fun resumeStandardReads() {
        enqueueRead(JbdProtocol.BASIC_INFO, "认证后读取基本状态")
        enqueueRead(JbdProtocol.CELL_VOLTAGES, "认证后读取单体电压")
        enqueueRead(JbdProtocol.HARDWARE_VERSION, "认证后识别硬件型号")
        enqueueProtectionParams()
    }

    //MARK:读取保护
    //readProtectionParameters 按目标协议构造保护，包含规定的帧头、长度、负载和校验字段。
    fun readProtectionParameters() {
        handler.post { enqueueProtectionParams() }
    }

    //MARK:入队保护参数
    //enqueueProtectionParams 推进保护参数的串行命令队列，确保一次只关联一个写入和响应。
    private fun enqueueProtectionParams() {
        enqueueCommand(
            PendingCommand(
                JbdProtocol.READ_PARAMETERS,
                JbdProtocol.readParametersCommand(startRegister = 2, count = 24),
                "读取保护参数"
            )
        )
    }

    //MARK:发现蓝牙服务
    //discoverServices 请求当前 GATT 发现全部服务；请求未启动时立即按连接失败处理。
    private fun discoverServices(gatt: BluetoothGatt) {
        if (this.gatt !== gatt || setupFinished) return
        if (!gatt.discoverServices()) failAndDisconnect("无法启动蓝牙服务识别")
    }

    //MARK:失败并断开
    //failAndDisconnect 记录明确的失败原因，停止通信任务并主动断开当前 GATT。
    private fun failAndDisconnect(reason: String) {
        if (gatt == null) {
            listener.onError(reason)
            return
        }
        pendingDisconnectReason = reason
        clearCommunicationState()
        gatt?.disconnect()
    }

    //MARK:清理通信
    //clearCommunicationState 取消所有通信超时与轮询，清空特征、命令队列、认证状态和暂存响应。
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

    //MARK:写特征优先级
    //按 JBD 标准 FF02、兼容 FFE1 及所属服务的可信程度，为可写特征计算选择优先级。
    private fun writePriority(characteristic: BluetoothGattCharacteristic): Int = when {
        characteristic.uuid.isShort("ff02") -> 100
        characteristic.uuid.isShort("ffe1") -> 90
        characteristic.service.uuid.isShort("ff00") -> 80
        characteristic.service.uuid.isShort("ffe0") -> 70
        else -> 1
    }

    //MARK:通知特征优先
    //按 JBD 标准 FF01、兼容 FFE1 及所属服务的可信程度，为通知特征计算选择优先级。
    private fun notifyPriority(characteristic: BluetoothGattCharacteristic): Int = when {
        characteristic.uuid.isShort("ff01") -> 100
        characteristic.uuid.isShort("ffe1") -> 90
        characteristic.service.uuid.isShort("ff00") -> 80
        characteristic.service.uuid.isShort("ffe0") -> 70
        else -> 1
    }

    //MARK:匹配短ID
    //判断完整 128 位 UUID 是否以指定的 16 位蓝牙短 UUID 开头，比较时忽略大小写。
    private fun UUID.isShort(value: String): Boolean =
        toString().lowercase().startsWith("0000${value.lowercase()}-")

    //MARK:缩写UUID
    //移除蓝牙基础 UUID 的固定后缀，生成便于日志和诊断页面阅读的短标签。
    private fun UUID.shortLabel(): String = toString().substringBefore("-0000-1000")

    //MARK:常量配置
    //定义 JBD 标准与兼容 UUID、扫描和通信超时、轮询间隔以及命令重试延迟。
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
