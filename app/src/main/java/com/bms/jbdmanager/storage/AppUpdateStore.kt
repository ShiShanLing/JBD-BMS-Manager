package com.bms.jbdmanager.storage

import android.content.Context

//MARK:更新存储
//AppUpdateStore 封装本地持久化、兼容解析和写回规则，用于处理应用更新。
internal class AppUpdateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    //MARK:读取跳过版本
    //skippedVersionCode 读取用户上次主动跳过的可选更新版本号；没有记录时返回 0。
    fun skippedVersionCode(): Int = preferences.getInt(KEY_SKIPPED_VERSION_CODE, 0)

    //MARK:跳过版本
    //skip 保存用户选择跳过的可选版本号；强制更新不会读取此设置，因此仍会正常提示。
    fun skip(versionCode: Int) {
        preferences.edit().putInt(KEY_SKIPPED_VERSION_CODE, versionCode).apply()
    }

    //MARK:常量配置
    //定义用户跳过版本号的偏好文件与字段键，仅用于非强制更新策略。
    private companion object {
        const val PREFERENCES_NAME = "jbd_app_update"
        const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"
    }
}
