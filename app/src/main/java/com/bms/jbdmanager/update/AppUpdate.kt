package com.bms.jbdmanager.update

import org.json.JSONObject

//MARK:更新配置
//AppUpdateConfig 提供进程内共享的应用更新能力，并集中维护其状态、常量或纯计算入口。
internal object AppUpdateConfig {
    const val VERSION_URL = "https://shishanling.cn/jbd-bms/version.json"
}

//MARK:更新记录
//AppUpdateEntry 将应用更新相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class AppUpdateEntry(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String = ""
)

//MARK:更新信息
//AppUpdateInfo 汇总一次应用更新信息的计算或读取结果，调用方无需再从原始字段重复推导。
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val forceUpdate: Boolean = false,
    val releaseNotes: String = "",
    val changelog: List<AppUpdateEntry> = emptyList()
) {
    //MARK:合并说明
    //notesSince 筛选版本号高于当前安装版本的更新说明，并按版本顺序组合展示。
    fun notesSince(currentVersionCode: Int): List<AppUpdateEntry> =
        buildList {
            add(AppUpdateEntry(versionCode, versionName, releaseNotes))
            addAll(changelog)
        }
            .filter { it.versionCode > currentVersionCode && it.releaseNotes.isNotBlank() }
            .distinctBy { it.versionCode }
            .sortedByDescending { it.versionCode }
}

//MARK:更新状态
//AppUpdateState 保存应用更新状态的当前快照；更新时通过 copy 生成新对象，使 StateFlow 能准确通知界面。
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

//MARK:更新策略
//AppUpdatePolicy 提供进程内共享的应用更新能力，并集中维护其状态、常量或纯计算入口。
internal object AppUpdatePolicy {
    //MARK:判断提示
    //shouldPrompt 结合版本新旧、强制更新标记和用户跳过记录，判断本次是否展示升级提示。
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

//MARK:更新清单解析
//AppUpdateManifestParser 提供进程内共享的应用更新版本清单能力，并集中维护其状态、常量或纯计算入口。
internal object AppUpdateManifestParser {
    //MARK:解析报文
    //parse 解析输入内容并校验必要字段，将合法数据转换为对应的结构化结果。
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
