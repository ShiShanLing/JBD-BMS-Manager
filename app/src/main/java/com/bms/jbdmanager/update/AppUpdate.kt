package com.bms.jbdmanager.update

import org.json.JSONObject

internal object AppUpdateConfig {
    const val VERSION_URL = "http://106.13.175.227/jbd-bms/version.json"
}

data class AppUpdateEntry(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String = ""
)

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val forceUpdate: Boolean = false,
    val releaseNotes: String = "",
    val changelog: List<AppUpdateEntry> = emptyList()
) {
    fun notesSince(currentVersionCode: Int): List<AppUpdateEntry> =
        buildList {
            add(AppUpdateEntry(versionCode, versionName, releaseNotes))
            addAll(changelog)
        }
            .filter { it.versionCode > currentVersionCode && it.releaseNotes.isNotBlank() }
            .distinctBy { it.versionCode }
            .sortedByDescending { it.versionCode }
}

data class AppUpdateState(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val latest: AppUpdateInfo? = null,
    val available: AppUpdateInfo? = null,
    val showPrompt: Boolean = false,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progressPercent: Int = 0,
    val apkFilePath: String? = null,
    val installRequestId: Int = 0,
    val statusMessage: String? = null,
    val checkError: String? = null
) {
    val hasNewerVersion: Boolean
        get() = (latest?.versionCode ?: 0) > currentVersionCode
}

internal object AppUpdatePolicy {
    fun shouldPrompt(
        info: AppUpdateInfo,
        currentVersionCode: Int,
        skippedVersionCode: Int
    ): Boolean {
        if (info.versionCode <= currentVersionCode) return false
        if (info.forceUpdate) return true
        return info.versionCode != skippedVersionCode
    }
}

internal object AppUpdateManifestParser {
    fun parse(json: String): AppUpdateInfo {
        val obj = JSONObject(json)
        val versionCode = obj.getInt("versionCode")
        val versionName = obj.getString("versionName").trim()
        val apkUrl = obj.getString("apkUrl").trim()
        require(versionCode > 0) { "versionCode 无效" }
        require(versionName.isNotEmpty()) { "缺少 versionName" }
        require(apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) {
            "apkUrl 必须是 http 或 https 地址"
        }
        val changelog = mutableListOf<AppUpdateEntry>()
        val array = obj.optJSONArray("changelog")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val code = item.optInt("versionCode", 0)
                if (code <= 0) continue
                changelog.add(
                    AppUpdateEntry(
                        versionCode = code,
                        versionName = item.optString("versionName").trim(),
                        releaseNotes = item.optString("releaseNotes").trim()
                    )
                )
            }
        }
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            forceUpdate = obj.optBoolean("forceUpdate", false),
            releaseNotes = obj.optString("releaseNotes").trim(),
            changelog = changelog
        )
    }
}
