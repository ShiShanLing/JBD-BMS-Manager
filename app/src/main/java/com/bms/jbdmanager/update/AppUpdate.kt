package com.bms.jbdmanager.update

internal object AppUpdateConfig {
    const val VERSION_URL = "http://106.13.175.227/jbd-bms/version.json"
}

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val forceUpdate: Boolean = false,
    val releaseNotes: String = ""
)

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
        val versionCode = requiredInt(json, "versionCode")
        val versionName = requiredString(json, "versionName")
        val apkUrl = requiredString(json, "apkUrl")
        require(versionCode > 0) { "versionCode 无效" }
        require(apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) {
            "apkUrl 必须是 http 或 https 地址"
        }
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            forceUpdate = optionalBoolean(json, "forceUpdate") ?: false,
            releaseNotes = optionalString(json, "releaseNotes").orEmpty().trim()
        )
    }

    private fun requiredInt(json: String, key: String): Int =
        optionalInt(json, key) ?: error("缺少 $key")

    private fun requiredString(json: String, key: String): String {
        val value = optionalString(json, key)?.trim().orEmpty()
        require(value.isNotEmpty()) { "缺少 $key" }
        return value
    }

    private fun optionalInt(json: String, key: String): Int? {
        val match = """"$key"\s*:\s*(-?\d+)""".toRegex().find(json) ?: return null
        return match.groupValues[1].toInt()
    }

    private fun optionalBoolean(json: String, key: String): Boolean? {
        val match = """"$key"\s*:\s*(true|false)""".toRegex().find(json) ?: return null
        return match.groupValues[1].toBooleanStrict()
    }

    private fun optionalString(json: String, key: String): String? {
        val match = """"$key"\s*:\s*"((?:\\.|[^"\\])*)"""".toRegex().find(json) ?: return null
        return unescape(match.groupValues[1])
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                append(char)
                index += 1
                continue
            }
            when (val escaped = value[index + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                '"' -> append('"')
                '\\' -> append('\\')
                '/' -> append('/')
                else -> append(escaped)
            }
            index += 2
        }
    }
}
