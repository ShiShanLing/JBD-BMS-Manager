package com.bms.jbdmanager.model

data class LastBmsSnapshot(
    val savedAtMillis: Long,
    val deviceAddress: String?,
    val deviceName: String?,
    val modelName: String?,
    val chipType: String?,
    val protocolProfile: String,
    val detectedProtocol: String?,
    val basicInfo: BmsBasicInfo,
    val cells: CellSummary?,
    val protectionParams: JbdProtectionParams?,
    val gpsSpeed: GpsSpeedState,
    val trip: TripState,
    val mileageHistory: MileageHistoryState
) {
    fun asUiState(): BmsUiState = BmsUiState(
        phase = ConnectionPhase.Idle,
        connectedAddress = deviceAddress,
        connectedName = deviceName,
        modelName = modelName,
        chipType = chipType,
        protocolProfile = protocolProfile,
        detectedProtocol = detectedProtocol,
        basicInfo = basicInfo,
        cells = cells,
        protectionParams = protectionParams,
        protectionParamsLoading = false,
        protectionParamsError = if (protectionParams == null) "最后一次连接未读取到保护参数" else null,
        dataFreshness = DataFreshness.Fresh,
        lastValidDataAtMillis = basicInfo.updatedAtMillis,
        locationPermissionGranted = true,
        gpsSpeed = gpsSpeed,
        trip = trip,
        mileageHistory = mileageHistory
    )
}
