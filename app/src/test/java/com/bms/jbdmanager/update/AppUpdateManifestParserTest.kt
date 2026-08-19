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
              "versionCode": 14,
              "versionName": "0.4.3",
              "apkUrl": "http://106.13.175.227/jbd-bms/latest.apk",
              "forceUpdate": false,
              "releaseNotes": "新增 App 更新提示\n修复连接稳定性"
            }
            """.trimIndent()
        )
        assertEquals(14, info.versionCode)
        assertEquals("0.4.3", info.versionName)
        assertEquals("http://106.13.175.227/jbd-bms/latest.apk", info.apkUrl)
        assertFalse(info.forceUpdate)
        assertEquals("新增 App 更新提示\n修复连接稳定性", info.releaseNotes)
    }

    @Test
    fun policyPromptsOnlyForNewerUnskippedVersions() {
        val info = AppUpdateInfo(
            versionCode = 14,
            versionName = "0.4.3",
            apkUrl = "http://106.13.175.227/jbd-bms/latest.apk"
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
