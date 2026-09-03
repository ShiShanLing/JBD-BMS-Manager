package com.bms.jbdmanager.report

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bms.jbdmanager.model.BatteryTrendState
import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.FullChargeDeltaSample
import com.bms.jbdmanager.model.FullChargeFingerprint
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.ProtectionEventSeverity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BatteryHealthPdfGeneratorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun reportIsMultiPageAndEveryPageRendersVisibleContent() {
        val output = File(context.getExternalFilesDir(null), "battery-health-report-sample.pdf")
        output.outputStream().use { BatteryHealthPdfGenerator().write(it, sampleState()) }

        assertTrue(output.length() > 10_000L)
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 3)
                repeat(renderer.pageCount) { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var nonWhite = 0
                        for (y in 0 until bitmap.height step 8) {
                            for (x in 0 until bitmap.width step 8) {
                                if (bitmap.getPixel(x, y) != Color.WHITE) nonWhite++
                            }
                        }
                        assertTrue("第${pageIndex + 1}页应包含可见内容", nonWhite > 25)
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun sampleState(): BmsUiState {
        val now = System.currentTimeMillis()
        val baselineCells = listOf(3492, 3494, 3491, 3493, 3495, 3492, 3494, 3493, 3492, 3494, 3493, 3492, 3495, 3493, 3494, 3492, 3493)
        val latestCells = listOf(3488, 3490, 3487, 3489, 3491, 3488, 3490, 3489, 3488, 3490, 3435, 3488, 3491, 3489, 3490, 3488, 3489)
        return BmsUiState(
            connectedAddress = "A5:C2:39:53:FB:40",
            connectedName = "DB24SA03L24S60ABU",
            modelName = "DB24SA03L24S60ABU",
            chipType = "凹凸方案",
            basicInfo = BmsBasicInfo(
                totalVoltageV = 55.70,
                currentA = 0.0,
                remainingCapacityAh = 25.95,
                nominalCapacityAh = 50.0,
                fullChargeCapacityAh = 48.0,
                stateOfChargePercent = 52,
                cycleCount = 126,
                temperaturesC = listOf(33.4, 32.2, 32.2),
                cellCount = 17,
                chargeMosEnabled = true,
                dischargeMosEnabled = true,
                balancingMask = 0,
                protectionMask = 0,
                alarmMask = 0,
                softwareVersion = "8.0",
                productionDate = "2026-07-06",
                humidityPercent = null,
                balancingCurrentMa = null
            ),
            cells = CellSummary(latestCells),
            capacityHealthRecords = (0 until 8).map { index ->
                CapacityHealthRecord(
                    id = now - index * 30L * DAY,
                    recordedAtMillis = now - index * 30L * DAY,
                    measuredDischargeAh = 47.5 - index * 0.25,
                    ratedCapacityAh = 50.0,
                    measuredDischargeWh = 2_450.0 - index * 15,
                    cycleCount = 126 - index * 12,
                    averageTemperatureC = 31.0 + index * 0.2,
                    note = "第${index + 1}次完整容量测试"
                )
            },
            batteryTrend = BatteryTrendState(
                fullChargeFingerprints = listOf(
                    FullChargeFingerprint(
                        capturedAtMillis = now - 180L * DAY,
                        totalVoltageV = baselineCells.sum() / 1_000.0,
                        socPercent = 100,
                        maximumTemperatureC = 30.0,
                        cellVoltagesMv = baselineCells
                    ),
                    FullChargeFingerprint(
                        capturedAtMillis = now,
                        totalVoltageV = latestCells.sum() / 1_000.0,
                        socPercent = 100,
                        maximumTemperatureC = 32.0,
                        cellVoltagesMv = latestCells
                    )
                ),
                fullChargeDeltas = listOf(
                    FullChargeDeltaSample(now - 180L * DAY, 28, 55.9, 0.2, 100, 30.0),
                    FullChargeDeltaSample(now - 90L * DAY, 24, 55.85, 0.1, 100, 31.0),
                    FullChargeDeltaSample(now, 18, 55.8, 0.2, 100, 32.0)
                )
            ),
            protectionEvents = listOf(
                ProtectionEvent(
                    id = 1,
                    protectionBit = 1,
                    title = "单体欠压",
                    startedAtMillis = now - 20L * DAY,
                    resolvedAtMillis = now - 20L * DAY + 8_000,
                    severity = ProtectionEventSeverity.Critical,
                    summary = "SOC尚未接近耗尽却触发欠压，需要检查容量和电芯状态",
                    stateOfChargePercent = 18,
                    totalVoltageV = 47.2,
                    currentA = -28.5,
                    minimumCellMv = 2490,
                    maximumCellMv = 2890,
                    cellDeltaMv = 400,
                    maximumTemperatureC = 38.2,
                    deviceAddress = "A5:C2:39:53:FB:40"
                ),
                ProtectionEvent(
                    id = 2,
                    protectionBit = 3,
                    title = "总压过低",
                    startedAtMillis = now - 60L * DAY,
                    resolvedAtMillis = now - 60L * DAY + 5_000,
                    severity = ProtectionEventSeverity.Expected,
                    summary = "电量接近耗尽时触发，按正常低电量截止记录",
                    stateOfChargePercent = 3,
                    totalVoltageV = 42.6,
                    currentA = -16.8,
                    deviceAddress = "A5:C2:39:53:FB:40"
                )
            )
        )
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1_000L
    }
}
