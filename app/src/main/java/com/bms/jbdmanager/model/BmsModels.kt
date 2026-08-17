package com.bms.jbdmanager.model

data class BmsBasicInfo(
    val totalVoltageV: Double,
    val currentA: Double,
    val remainingCapacityAh: Double,
    val nominalCapacityAh: Double,
    val fullChargeCapacityAh: Double?,
    val stateOfChargePercent: Int,
    val cycleCount: Int,
    val temperaturesC: List<Double>,
    val cellCount: Int,
    val chargeMosEnabled: Boolean,
    val dischargeMosEnabled: Boolean,
    val balancingMask: Long,
    val protectionMask: Int,
    val alarmMask: Int?,
    val softwareVersion: String,
    val productionDate: String?,
    val humidityPercent: Int?,
    val balancingCurrentMa: Int?,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val estimatedSohPercent: Double?
        get() = fullChargeCapacityAh
            ?.takeIf { nominalCapacityAh > 0.0 }
            ?.let { (it / nominalCapacityAh * 100.0).coerceIn(0.0, 150.0) }
}

data class CellSummary(
    val millivolts: List<Int>,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val minimumMv: Int? get() = millivolts.minOrNull()
    val maximumMv: Int? get() = millivolts.maxOrNull()
    val deltaMv: Int? get() = minimumMv?.let { min -> maximumMv?.minus(min) }
}

data class ScanDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val looksLikeJbd: Boolean
)

data class SavedDevice(
    val address: String,
    val name: String
)

enum class ConnectionPhase {
    Idle,
    Scanning,
    Connecting,
    Reconnecting,
    Discovering,
    Ready,
    Disconnecting,
    Error
}

enum class DataFreshness {
    Waiting,
    Fresh,
    Stale
}

data class RawLogEntry(
    val timestampMillis: Long,
    val direction: Direction,
    val hex: String,
    val note: String
) {
    enum class Direction { Tx, Rx, Info, Error }
}

data class BmsUiState(
    val bluetoothSupported: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val permissionsGranted: Boolean = false,
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val devices: List<ScanDevice> = emptyList(),
    val savedDevices: List<SavedDevice> = emptyList(),
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val connectedAddress: String? = null,
    val connectedName: String? = null,
    val reconnectAttempt: Int = 0,
    val reconnectInSeconds: Int? = null,
    val modelName: String? = null,
    val protocolProfile: String = "等待识别",
    val detectedProtocol: String? = null,
    val bleChannelDetails: String? = null,
    val chipType: String? = null,
    val basicInfo: BmsBasicInfo? = null,
    val cells: CellSummary? = null,
    val dataFreshness: DataFreshness = DataFreshness.Waiting,
    val communicationReadyAtMillis: Long? = null,
    val lastValidDataAtMillis: Long? = null,
    val lastDataAgeSeconds: Int? = null,
    val authenticationRequired: Boolean = false,
    val authenticationMessage: String? = null,
    val sessionChargeAh: Double = 0.0,
    val sessionDischargeAh: Double = 0.0,
    val logs: List<RawLogEntry> = emptyList(),
    val errorMessage: String? = null
)
