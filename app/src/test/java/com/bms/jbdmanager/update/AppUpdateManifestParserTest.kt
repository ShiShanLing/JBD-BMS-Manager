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
