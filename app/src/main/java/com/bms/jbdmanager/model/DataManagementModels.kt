package com.bms.jbdmanager.model

//MARK:恢复预览
//BackupRestorePreview 汇总一次备份恢复的计算或读取结果，调用方无需再从原始字段重复推导。
data class BackupRestorePreview(
    val createdAtMillis: Long,
    val sourceVersionName: String,
    val trendSampleCount: Int,
    val dailySummaryCount: Int,
    val fullChargeFingerprintCount: Int,
    val fullChargeDeltaCount: Int = 0,
    val preferenceGroupCount: Int
)

//MARK:数据状态
//DataManagementState 保存数据状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
data class DataManagementState(
    val working: Boolean = false,
    val operationLabel: String? = null,
    val pendingRestore: BackupRestorePreview? = null,
    val statusMessage: String? = null,
    val healthPdfPreviewPath: String? = null
)

//MARK:数据快照
//DataExportSnapshot 将数据快照相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class DataExportSnapshot(
    val capacityRecords: List<CapacityHealthRecord>,
    val protectionEvents: List<ProtectionEvent>,
    val mileageSessions: List<TripSessionRecord>,
    val tripState: TripState
)
