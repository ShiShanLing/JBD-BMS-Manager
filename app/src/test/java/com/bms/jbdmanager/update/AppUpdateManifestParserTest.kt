package com.bms.jbdmanager.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestParserTest {
    @Test
    fun parseReadsLatestManifest() {
        val info = AppUpdateManifestParser.parse(
            """
            {
              "versionCode": 16,
              "versionName": "0.4.5",
              "apkUrl": "https://github.com/ShiShanLing/JBD-BMS-Manager/releases/download/v0.4.5/JBD-BMS-Manager-v0.4.5.apk",
              "forceUpdate": false,
              "releaseNotes": "新增 App 更新提示\n修复连接稳定性"
            }
            """.trimIndent()
        )
        assertEquals(16, info.versionCode)
        assertEquals("0.4.5", info.versionName)
        assertEquals(
            "https://github.com/ShiShanLing/JBD-BMS-Manager/releases/download/v0.4.5/JBD-BMS-Manager-v0.4.5.apk",
            info.apkUrl
        )
        assertFalse(info.forceUpdate)
        assertEquals("新增 App 更新提示\n修复连接稳定性", info.releaseNotes)
        assertTrue(info.changelog.isEmpty())
    }

    @Test
    fun notesSinceIncludesSkippedVersionsNewestFirst() {
        val info = AppUpdateManifestParser.parse(
            """
            {
              "versionCode": 33,
              "versionName": "0.5.3",
              "apkUrl": "https://example.com/app.apk",
              "releaseNotes": "更新弹窗会列出跳过的版本说明",
              "changelog": [
                {"versionCode": 32, "versionName": "0.5.2", "releaseNotes": "名称改为电动 BMS"},
                {"versionCode": 30, "versionName": "0.5.0", "releaseNotes": "新增画中画小窗"}
              ]
            }
            """.trimIndent()
        )
        val notes = info.notesSince(17)
        assertEquals(listOf("0.5.3", "0.5.2", "0.5.0"), notes.map { it.versionName })
        assertEquals(listOf("0.5.3"), info.notesSince(32).map { it.versionName })
        assertTrue(info.notesSince(33).isEmpty())
    }

    @Test
    fun policyPromptsOnlyForNewerUnskippedVersions() {
        val info = AppUpdateInfo(
            versionCode = 14,
            versionName = "0.4.3",
            apkUrl = "https://github.com/ShiShanLing/JBD-BMS-Manager/releases/download/v0.4.5/JBD-BMS-Manager-v0.4.5.apk"
        )
        assertFalse(AppUpdatePolicy.shouldPrompt(info, currentVersionCode = 14, skippedVersionCode = 0))
        assertTrue(AppUpdatePolicy.shouldPrompt(info, currentVersionCode = 13, skippedVersionCode = 0))
        assertFalse(AppUpdatePolicy.shouldPrompt(info, currentVersionCode = 13, skippedVersionCode = 14))
        assertTrue(
            AppUpdatePolicy.shouldPrompt(
                info.copy(forceUpdate = true),
                currentVersionCode = 13,
                skippedVersionCode = 14
            )
        )
    }
}
