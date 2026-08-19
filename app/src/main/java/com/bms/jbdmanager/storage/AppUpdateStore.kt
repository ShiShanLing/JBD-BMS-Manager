package com.bms.jbdmanager.storage

import android.content.Context

internal class AppUpdateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun skippedVersionCode(): Int = preferences.getInt(KEY_SKIPPED_VERSION_CODE, 0)

    fun skip(versionCode: Int) {
        preferences.edit().putInt(KEY_SKIPPED_VERSION_CODE, versionCode).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "jbd_app_update"
        const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"
    }
}
