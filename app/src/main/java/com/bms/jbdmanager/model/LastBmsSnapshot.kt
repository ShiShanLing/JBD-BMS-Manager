package com.bms.jbdmanager.model

//MARK:末次电池快照
//LastBmsSnapshot 将最后一次快照相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
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
    //MARK:还原界面状态
    //asUiState 把离线快照转换为只读页面状态，并确保连接状态和实时速度不会被误认为仍在运行。
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
