package com.bms.jbdmanager.model

data class BackupRestorePreview(
    val createdAtMillis: Long,
    val sourceVersionName: String,
    val trendSampleCount: Int,
    val dailySummaryCount: Int,
    val fullChargeFingerprintCount: Int,
    val fullChargeDeltaCount: Int = 0,
    val preferenceGroupCount: Int
)

data class DataManagementState(
    val working: Boolean = false,
    val operationLabel: String? = null,
    val pendingRestore: BackupRestorePreview? = null,
    val statusMessage: String? = null,
    val healthPdfPreviewPath: String? = null
)

data class DataExportSnapshot(
    val capacityRecords: List<CapacityHealthRecord>,
    val protectionEvents: List<ProtectionEvent>,
    val mileageSessions: List<TripSessionRecord>,
    val tripState: TripState
)
