package com.bms.jbdmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.bms.jbdmanager.ble.JbdBleListener
import com.bms.jbdmanager.ble.JbdBleManager
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.RawLogEntry
import com.bms.jbdmanager.model.ScanDevice
import com.bms.jbdmanager.model.SavedDevice
import com.bms.jbdmanager.protocol.JbdFrameAssembler
import com.bms.jbdmanager.protocol.JbdMessage
import com.bms.jbdmanager.protocol.JbdProtocol
import com.bms.jbdmanager.protocol.JbdProtocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

class BmsViewModel(application: Application) : AndroidViewModel(application), JbdBleListener {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        BmsUiState(
            savedDevices = loadSavedDevices(),
            lastDeviceAddress = preferences.getString(LAST_DEVICE_ADDRESS, null),
            lastDeviceName = preferences.getString(LAST_DEVICE_NAME, null)
        )
    )
    val uiState: StateFlow<BmsUiState> = _uiState.asStateFlow()

    private val frameAssembler = JbdFrameAssembler()
    private val bleManager = JbdBleManager(application, this)
    private var lastCapacitySampleAt: Long? = null
    private var autoConnectAttempted = false

    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) bleManager.refreshBluetoothState()
    }

    fun refreshBluetoothState() = bleManager.refreshBluetoothState()

    fun startScan() {
        if (!_uiState.value.permissionsGranted) {
            onError("请先允许附近设备权限")
            return
        }
        _uiState.update { it.copy(errorMessage = null, devices = emptyList()) }
        bleManager.startScan()
    }

    fun stopScan() = bleManager.stopScan()

    fun connect(address: String) {
        autoConnectAttempted = true
        _uiState.update { it.copy(errorMessage = null) }
        frameAssembler.clear()
        lastCapacitySampleAt = null
        bleManager.connect(address)
    }

    fun disconnect() {
        _uiState.update { it.copy(phase = ConnectionPhase.Disconnecting) }
        bleManager.disconnect()
    }

    fun clearLogs() = _uiState.update { it.copy(logs = emptyList()) }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    override fun onBluetoothState(supported: Boolean, enabled: Boolean) {
        _uiState.update { it.copy(bluetoothSupported = supported, bluetoothEnabled = enabled) }
        tryAutoConnect(supported, enabled)
    }

    override fun onScanStarted() {
        addLog(RawLogEntry.Direction.Info, "", "开始扫描附近 BLE 设备")
        _uiState.update { it.copy(phase = ConnectionPhase.Scanning, errorMessage = null) }
    }

    override fun onScanResult(devices: List<ScanDevice>) {
        _uiState.update { it.copy(devices = devices) }
    }

    override fun onScanStopped() {
        _uiState.update {
            if (it.phase == ConnectionPhase.Scanning) it.copy(phase = ConnectionPhase.Idle) else it
        }
    }

    override fun onConnecting(address: String, name: String) {
        addLog(RawLogEntry.Direction.Info, "", "正在连接 $name ($address)")
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Connecting,
                connectedAddress = address,
                connectedName = name,
                modelName = null,
                protocolProfile = "正在探测",
                chipType = null,
                basicInfo = null,
                cells = null,
                sessionChargeAh = 0.0,
                sessionDischargeAh = 0.0
            )
        }
    }

    override fun onDiscovering() {
        _uiState.update { it.copy(phase = ConnectionPhase.Discovering) }
        addLog(RawLogEntry.Direction.Info, "", "已连接，正在发现服务与特征")
    }

    override fun onReady(profile: String) {
        val address = _uiState.value.connectedAddress
        val name = _uiState.value.connectedName
        address?.let {
            val savedAddresses = preferences.getStringSet(SAVED_DEVICE_ADDRESSES, emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { add(it) }
            preferences.edit()
                .putString(LAST_DEVICE_ADDRESS, it)
                .putString(LAST_DEVICE_NAME, name)
                .putStringSet(SAVED_DEVICE_ADDRESSES, savedAddresses)
                .putString("$SAVED_DEVICE_NAME_PREFIX$it", name)
                .apply()
        }
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Ready,
                protocolProfile = profile,
                errorMessage = null,
                lastDeviceAddress = address ?: it.lastDeviceAddress,
                lastDeviceName = name ?: it.lastDeviceName,
                savedDevices = if (address == null) it.savedDevices else {
                    listOf(SavedDevice(address, name.orEmpty().ifBlank { address })) +
                        it.savedDevices.filterNot { saved -> saved.address == address }
                }
            )
        }
        addLog(RawLogEntry.Direction.Info, "", "通信就绪：$profile")
    }

    override fun onDisconnected(reason: String?) {
        frameAssembler.clear()
        lastCapacitySampleAt = null
        _uiState.update {
            it.copy(
                phase = ConnectionPhase.Idle,
                connectedAddress = null,
                errorMessage = reason
            )
        }
        addLog(RawLogEntry.Direction.Info, "", reason?.let { "连接断开：$it" } ?: "连接已断开")
    }

    override fun onPacketSent(packet: ByteArray, note: String) {
        addLog(RawLogEntry.Direction.Tx, packet.toHex(), note)
    }

    override fun onNotification(bytes: ByteArray) {
        val frames = frameAssembler.append(bytes)
        if (frames.isEmpty() && bytes.firstOrNull()?.toInt()?.and(0xFF) != 0xDD) {
            addLog(RawLogEntry.Direction.Rx, bytes.toHex(), "非标准通知数据")
        }
        frames.forEach(::handleFrame)
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
        addLog(RawLogEntry.Direction.Error, "", message)
    }

    private fun handleFrame(raw: ByteArray) {
        addLog(RawLogEntry.Direction.Rx, raw.toHex(), "BMS 响应")
        val frame = JbdProtocol.decode(raw).getOrElse {
            onError("报文解析失败：${it.message}")
            return
        }
        val message = JbdProtocol.parse(frame).getOrElse {
            onError("数据字段解析失败：${it.message}")
            return
        }

        when (message) {
            is JbdMessage.BasicInfo -> {
                integrateCapacity(message.value.currentA, message.value.updatedAtMillis)
                val baseLength = 23 + message.value.temperaturesC.size * 2
                _uiState.update {
                    it.copy(
                        basicInfo = message.value,
                        protocolProfile = if (frame.data.size > baseLength) {
                            "${it.protocolProfile.substringBefore(" ·")} · V12 扩展状态"
                        } else it.protocolProfile
                    )
                }
            }
            is JbdMessage.Cells -> {
                val declaredCount = _uiState.value.basicInfo?.cellCount
                if (declaredCount != null && declaredCount > 0 && declaredCount != message.value.millivolts.size) {
                    addLog(
                        RawLogEntry.Direction.Error,
                        frame.raw.toHex(),
                        "串数不一致：基本信息 $declaredCount 串，电压数据 ${message.value.millivolts.size} 串"
                    )
                }
                _uiState.update { it.copy(cells = message.value) }
            }
            is JbdMessage.HardwareVersion -> {
                _uiState.update { it.copy(modelName = message.value) }
                addLog(RawLogEntry.Direction.Info, "", "识别到型号：${message.value}")
            }
            is JbdMessage.ChipType -> _uiState.update { it.copy(chipType = message.value) }
            is JbdMessage.Unsupported -> {
                val reason = when (message.status) {
                    0x80 -> "设备不支持命令"
                    0x81 -> "操作无效或需要工厂模式"
                    0x82 -> "设备报告校验错误"
                    0x83 -> "蓝牙需要密码认证"
                    else -> "状态码 0x${message.status.toString(16).uppercase()}"
                }
                addLog(RawLogEntry.Direction.Info, frame.raw.toHex(), reason)
            }
            is JbdMessage.Unknown -> addLog(
                RawLogEntry.Direction.Info,
                message.data.toHex(),
                "未知只读响应 0x${message.command.toString(16).uppercase()}"
            )
        }
    }

    private fun integrateCapacity(currentA: Double, now: Long) {
        val previous = lastCapacitySampleAt
        lastCapacitySampleAt = now
        if (previous == null) return
        val elapsedHours = ((now - previous).coerceIn(0, 5_000)) / 3_600_000.0
        val amount = abs(currentA) * elapsedHours
        _uiState.update {
            if (currentA >= 0) it.copy(sessionChargeAh = it.sessionChargeAh + amount)
            else it.copy(sessionDischargeAh = it.sessionDischargeAh + amount)
        }
    }

    private fun addLog(direction: RawLogEntry.Direction, hex: String, note: String) {
        val entry = RawLogEntry(System.currentTimeMillis(), direction, hex, note)
        _uiState.update { state -> state.copy(logs = (listOf(entry) + state.logs).take(250)) }
    }

    override fun onCleared() {
        bleManager.close()
        super.onCleared()
    }

    private fun tryAutoConnect(supported: Boolean, enabled: Boolean) {
        if (autoConnectAttempted || !supported || !enabled || !_uiState.value.permissionsGranted) return
        if (_uiState.value.phase != ConnectionPhase.Idle) return
        val address = preferences.getString(LAST_DEVICE_ADDRESS, null) ?: return
        autoConnectAttempted = true
        val name = preferences.getString(LAST_DEVICE_NAME, null).orEmpty().ifBlank { address }
        addLog(RawLogEntry.Direction.Info, "", "正在自动连接上次设备：$name")
        bleManager.connect(address)
    }

    private fun loadSavedDevices(): List<SavedDevice> {
        val lastAddress = preferences.getString(LAST_DEVICE_ADDRESS, null)
        val addresses = preferences.getStringSet(SAVED_DEVICE_ADDRESSES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { lastAddress?.let(::add) }
        return addresses.map { address ->
            SavedDevice(
                address = address,
                name = preferences.getString("$SAVED_DEVICE_NAME_PREFIX$address", null)
                    ?: (if (address == lastAddress) preferences.getString(LAST_DEVICE_NAME, null) else null)
                    ?: address
            )
        }.sortedByDescending { it.address == lastAddress }
    }

    companion object {
        private const val PREFERENCES_NAME = "jbd_bms_preferences"
        private const val LAST_DEVICE_ADDRESS = "last_device_address"
        private const val LAST_DEVICE_NAME = "last_device_name"
        private const val SAVED_DEVICE_ADDRESSES = "saved_device_addresses"
        private const val SAVED_DEVICE_NAME_PREFIX = "saved_device_name_"
    }
}
