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
    val gpsSpeed: GpsSpeedState,
    val trip: TripState
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
        dataFreshness = DataFreshness.Fresh,
        lastValidDataAtMillis = basicInfo.updatedAtMillis,
        locationPermissionGranted = true,
        gpsSpeed = gpsSpeed,
        trip = trip
    )
}
