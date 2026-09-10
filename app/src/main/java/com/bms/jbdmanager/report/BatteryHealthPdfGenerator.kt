package com.bms.jbdmanager.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.CapacityHealthRecord
import com.bms.jbdmanager.model.CellHealthDiagnosis
import com.bms.jbdmanager.model.FullChargeDeltaDirection
import com.bms.jbdmanager.model.FullChargeDeltaTrend
import com.bms.jbdmanager.model.HealthDiagnosisConfidence
import com.bms.jbdmanager.model.HealthDiagnosisFinding
import com.bms.jbdmanager.model.HealthDiagnosisLevel
import com.bms.jbdmanager.model.ProtectionEvent
import com.bms.jbdmanager.model.diagnoseBatteryHealth
import com.bms.jbdmanager.model.evaluateFullChargeDeltaTrend
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

//MARK:健康报告生成
//BatteryHealthPdfGenerator 把业务数据排版并生成可交付文件，用于处理电池健康PDF。
internal class BatteryHealthPdfGenerator {
    //MARK:写入数据
    //根据电池快照、容量记录、趋势和保护事件生成完整 PDF，并通过 use 保证文档与输出流正确关闭。
    fun write(output: OutputStream, state: BmsUiState, generatedAtMillis: Long = System.currentTimeMillis()) {
        val deviceAddress = state.connectedAddress ?: state.lastSnapshot?.deviceAddress
        val diagnosis = diagnoseBatteryHealth(
            capacityRecords = state.capacityHealthRecords,
            fingerprints = state.batteryTrend.fullChargeFingerprints,
            protectionEvents = state.protectionEvents,
            deviceAddress = deviceAddress,
            fullChargeDeltas = state.batteryTrend.fullChargeDeltas
        )
        val report = PdfReportDocument(generatedAtMillis)
        try {
            val basic = state.basicInfo ?: state.lastSnapshot?.basicInfo
            val cells = state.cells ?: state.lastSnapshot?.cells
            val matchingEvents = state.protectionEvents
                .filter { deviceAddress == null || it.deviceAddress == null || it.deviceAddress == deviceAddress }
                .sortedByDescending { it.startedAtMillis }

            report.title("电池健康诊断报告", "生成时间 ${formatDateTime(generatedAtMillis)}")
            report.infoGrid(
                listOf(
                    "设备名称" to (state.connectedName ?: state.lastSnapshot?.deviceName ?: "未命名设备"),
                    "设备地址" to (deviceAddress ?: "--"),
                    "BMS型号" to (state.modelName ?: state.lastSnapshot?.modelName ?: "未识别"),
                    "软件版本" to (basic?.softwareVersion ?: "未读取"),
                    "BMS总容量" to basic?.nominalCapacityAh?.let { "${number(it)} Ah" }.orEmpty().ifBlank { "未读取" },
                    "循环次数" to basic?.cycleCount?.toString().orEmpty().ifBlank { "未读取" }
                )
            )

            report.section("诊断结论")
            report.diagnosisBanner(
                diagnosis.summary,
                diagnosis.overallLevel,
                "可信度 ${confidenceLabel(diagnosis.confidence)} · " +
                    "${diagnosis.capacityTestCount}次容量测试 · ${diagnosis.comparableFingerprintCount}次可比满充记录"
            )
            diagnosis.findings.forEach(report::finding)

            report.section("当前关键数据")
            report.metricGrid(
                listOf(
                    "SOC" to basic?.let { "${it.stateOfChargePercent}%" }.orEmpty().ifBlank { "--" },
                    "总压" to basic?.let { "${number(it.totalVoltageV, 2)} V" }.orEmpty().ifBlank { "--" },
                    "剩余/总容量" to basic?.let {
                        "${number(it.remainingCapacityAh)}/${number(it.nominalCapacityAh)} Ah"
                    }.orEmpty().ifBlank { "--" },
                    "最高温度" to basic?.temperaturesC?.maxOrNull()?.let { "${number(it, 1)} ℃" }.orEmpty().ifBlank { "--" },
                    "最低单体" to cells?.minimumMv?.let { "${it} mV" }.orEmpty().ifBlank { "--" },
                    "单体压差" to cells?.deltaMv?.let { "${it} mV" }.orEmpty().ifBlank { "--" }
                )
            )

            report.capacityChart(state.capacityHealthRecords)

            report.section("容量测试记录")
            report.note("容量健康度只采用完整放电测试的实测Ah计算，BMS估算值和骑行SOC变化不作为实测容量。")
            val qualifiedCapacityRecords = state.capacityHealthRecords.filter { it.qualifiedForHealth }
            report.capacityTable(qualifiedCapacityRecords.sortedByDescending { it.recordedAtMillis }.take(20))
            if (qualifiedCapacityRecords.size > 20) {
                report.note("PDF仅展示最近20次正式容量测试，完整记录请查看CSV资料包。")
            }

            report.newPageSection("满充单体长期对比")
            val deltaTrend = evaluateFullChargeDeltaTrend(state.batteryTrend.fullChargeDeltas)
            report.fullChargeDeltaChart(deltaTrend)
            val baseline = diagnosis.baselineFingerprint
            val latest = diagnosis.latestFingerprint
            report.note(
                if (baseline != null && latest != null) {
                    "比较区间 ${formatDate(baseline.capturedAtMillis)} 至 ${formatDate(latest.capturedAtMillis)}。" +
                        "相对变化已减去整组共同升降，用于降低充电截止电压和温度造成的误判。"
                } else {
                    "目前缺少至少间隔7天、SOC差不超过2%、最高温差不超过7℃的可比满充记录。"
                }
            )
            if (diagnosis.cells.isNotEmpty()) {
                report.cellTable(diagnosis.cells.sortedBy { it.cellNumber })
            } else if (!cells?.millivolts.isNullOrEmpty()) {
                report.currentCellTable(cells!!.millivolts)
            } else {
                report.emptyMessage("尚未保存单体电压数据。")
            }

            report.section("保护与异常证据")
            report.protectionEvents(matchingEvents.take(30))
            if (matchingEvents.size > 30) {
                report.note("PDF仅展示最近30条保护记录，完整记录请查看CSV资料包。")
            }

            report.section("判断条件与使用说明")
            report.bullet("容量健康：实测容量/BMS总容量；90%以上为正常，80%-89.9%持续观察，75%-79.9%警告，低于75%严重。")
            report.bullet("满充压差：连接且接近满充（SOC≥99%，或剩余容量接近满充Ah）时记录整组最高与最低单体之差；压差变小视为一致性向好。")
            report.bullet("单体一致性：仅比较串数相同、SOC差不超过2%、最高温差不超过7℃且至少间隔7天的满充记录。")
            report.bullet("单体绝对电压会受充电器截止、电池温度、充电电流和静置时间影响，应结合相对漂移及多次记录判断。")
            report.bullet("本报告由App本地只读数据自动生成，不是电池厂商或法定检测机构出具的容量鉴定证明。")
            report.writeTo(output)
        } finally {
            report.close()
        }
    }
}

//MARK:PDF绘制器
//PdfReportDocument 封装 PdfDocument、当前画布和纵向游标，为各报告区块提供统一分页与绘制能力。
private class PdfReportDocument(private val generatedAtMillis: Long) {
    private val document = PdfDocument()
    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = CONTENT_TOP
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular }

    init {
        startPage()
    }

    //MARK:绘制报告标题
    //title 绘制报告主标题和副标题，并为后续内容预留垂直间距。
    fun title(title: String, subtitle: String) {
        text(title, 26f, COLOR_TEXT, medium)
        text(subtitle, 10f, COLOR_MUTED)
        y += 8f
    }

    //MARK:绘制信息表格
    //infoGrid 将信息写入当前 PDF 页面，并在剩余空间不足时自动换页。
    fun infoGrid(items: List<Pair<String, String>>) {
        items.chunked(2).forEach { row ->
            ensureSpace(38f)
            row.forEachIndexed { index, item ->
                val x = MARGIN + index * (CONTENT_WIDTH / 2f + 6f)
                val width = CONTENT_WIDTH / 2f - 6f
                canvas.drawRoundRect(RectF(x, y, x + width, y + 32f), 6f, 6f, fill(COLOR_CARD))
                drawText(item.first, x + 8f, y + 11f, 8f, COLOR_MUTED)
                drawFittedText(item.second, x + 8f, y + 25f, width - 16f, 11f, COLOR_TEXT, medium)
            }
            y += 38f
        }
    }

    //MARK:绘制报告章节
    //section 绘制带主题色竖线的章节标题，空间不足时先自动换页。
    fun section(title: String) {
        ensureSpace(36f)
        y += 10f
        canvas.drawRoundRect(RectF(MARGIN, y, MARGIN + 4f, y + 18f), 2f, 2f, fill(COLOR_PRIMARY))
        drawText(title, MARGIN + 11f, y + 14f, 15f, COLOR_TEXT, medium)
        y += 27f
    }

    //MARK:新页绘制章节
    //newPageSection 绘制页面标题并更新纵向位置，必要时先结束当前页并创建新页。
    fun newPageSection(title: String) {
        if (y > CONTENT_TOP + 20f) startPage()
        section(title)
    }

    //MARK:绘制诊断横幅
    //diagnosisBanner 将诊断写入当前 PDF 页面，并在剩余空间不足时自动换页。
    fun diagnosisBanner(summary: String, level: HealthDiagnosisLevel, evidence: String) {
        val color = levelColor(level)
        ensureSpace(66f)
        canvas.drawRoundRect(RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + 58f), 10f, 10f, fill(blendWithWhite(color, 0.88f)))
        canvas.drawCircle(MARGIN + 18f, y + 21f, 6f, fill(color))
        drawText(summary, MARGIN + 32f, y + 25f, 15f, color, medium)
        drawText(evidence, MARGIN + 32f, y + 44f, 9f, COLOR_MUTED)
        y += 66f
    }

    //MARK:绘制诊断结论
    //finding 将诊断项写入当前 PDF 页面，并在剩余空间不足时自动换页。
    fun finding(finding: HealthDiagnosisFinding) {
        val detailPaint = configuredPaint(9f, COLOR_MUTED)
        val lines = wrapLines(finding.detail, detailPaint, CONTENT_WIDTH - 38f)
        val height = 29f + lines.size * 12f
        ensureSpace(height + 6f)
        canvas.drawRoundRect(RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + height), 7f, 7f, fill(COLOR_CARD))
        val color = levelColor(finding.level)
        canvas.drawCircle(MARGIN + 12f, y + 15f, 4f, fill(color))
        drawText(finding.title, MARGIN + 23f, y + 19f, 11f, color, medium)
        drawLines(lines, MARGIN + 23f, y + 34f, detailPaint, 12f)
        y += height + 6f
    }

    //MARK:绘制指标网格
    //metricGrid 将指标写入当前 PDF 页面，并在剩余空间不足时自动换页。
    fun metricGrid(items: List<Pair<String, String>>) {
        items.chunked(3).forEach { row ->
            ensureSpace(54f)
            val gap = 6f
            val width = (CONTENT_WIDTH - gap * 2) / 3f
            row.forEachIndexed { index, item ->
                val x = MARGIN + index * (width + gap)
                canvas.drawRoundRect(RectF(x, y, x + width, y + 47f), 7f, 7f, fill(COLOR_CARD))
                drawText(item.first, x + 8f, y + 15f, 8f, COLOR_MUTED)
                drawFittedText(item.second, x + 8f, y + 34f, width - 16f, 13f, COLOR_TEXT, medium)
            }
            y += 54f
        }
    }

    //MARK:绘制容量趋势
    //capacityChart 把容量图表样本缩放到绘图区，绘制坐标、连线和关键数值标签。
    fun capacityChart(records: List<CapacityHealthRecord>) {
        val values = records.filter { it.qualifiedForHealth }.sortedBy { it.recordedAtMillis }.takeLast(12)
        ensureSpace(if (values.isEmpty()) 80f else 205f)
        section("实测容量趋势")
        if (values.isEmpty()) {
            emptyMessage("尚无完整容量测试，无法绘制容量健康趋势。")
            return
        }
        val left = MARGIN + 34f
        val top = y + 8f
        val right = PAGE_WIDTH - MARGIN - 8f
        val bottom = y + 130f
        val minimum = min(70.0, values.minOf { it.sohPercent } - 5.0)
        val maximum = max(105.0, values.maxOf { it.sohPercent } + 3.0)
        repeat(4) { index ->
            val lineY = top + (bottom - top) * index / 3f
            canvas.drawLine(left, lineY, right, lineY, stroke(COLOR_LINE, 0.7f))
            val label = maximum - (maximum - minimum) * index / 3.0
            drawText("${number(label, 0)}%", MARGIN, lineY + 3f, 7f, COLOR_MUTED)
        }
        val path = Path()
        values.forEachIndexed { index, record ->
            val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * index / (values.size - 1)
            val pointY = bottom - ((record.sohPercent - minimum) / (maximum - minimum) * (bottom - top)).toFloat()
            if (index == 0) path.moveTo(x, pointY) else path.lineTo(x, pointY)
            canvas.drawCircle(x, pointY, 3.5f, fill(levelColor(capacityLevel(record.sohPercent))))
            drawText(formatShortDate(record.recordedAtMillis), x - 10f, bottom + 16f, 7f, COLOR_MUTED)
        }
        canvas.drawPath(path, stroke(COLOR_PRIMARY, 2f))
        y += 150f
    }

    //MARK:绘制满充压差
    //fullChargeDeltaChart 把满充充电压差图表样本缩放到绘图区，绘制坐标、连线和关键数值标签。
    fun fullChargeDeltaChart(trend: FullChargeDeltaTrend) {
        val values = trend.samples
        ensureSpace(if (values.size < 2) 92f else 205f)
        section("满充压差趋势")
        note(trend.summary)
        if (values.size < 2) return
        val left = MARGIN + 34f
        val top = y + 8f
        val right = PAGE_WIDTH - MARGIN - 8f
        val bottom = y + 130f
        val rawMin = values.minOf { it.cellDeltaMv }.toDouble()
        val rawMax = values.maxOf { it.cellDeltaMv }.toDouble()
        val padding = max(2.0, (rawMax - rawMin) * 0.12)
        val minimum = rawMin - padding
        val maximum = rawMax + padding
        val color = when (trend.direction) {
            FullChargeDeltaDirection.Improving -> COLOR_PRIMARY
            FullChargeDeltaDirection.Worsening -> COLOR_WARNING
            else -> COLOR_MUTED
        }
        repeat(4) { index ->
            val lineY = top + (bottom - top) * index / 3f
            canvas.drawLine(left, lineY, right, lineY, stroke(COLOR_LINE, 0.7f))
            val label = maximum - (maximum - minimum) * index / 3.0
            drawText("${number(label, 0)}", MARGIN, lineY + 3f, 7f, COLOR_MUTED)
        }
        val path = Path()
        values.forEachIndexed { index, sample ->
            val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * index / (values.size - 1)
            val pointY = bottom - ((sample.cellDeltaMv - minimum) / (maximum - minimum) * (bottom - top)).toFloat()
            if (index == 0) path.moveTo(x, pointY) else path.lineTo(x, pointY)
            canvas.drawCircle(x, pointY, 3.5f, fill(color))
            if (index == 0 || index == values.lastIndex || values.size <= 6) {
                drawText(formatShortDate(sample.capturedAtMillis), x - 10f, bottom + 16f, 7f, COLOR_MUTED)
            }
        }
        canvas.drawPath(path, stroke(color, 2f))
        y += 150f
    }

    //MARK:绘制报告备注
    //note 将可换行的说明文字写入报告，并根据实际行数推进纵向位置。
    fun note(value: String) {
        val notePaint = configuredPaint(9f, COLOR_MUTED)
        val lines = wrapLines(value, notePaint, CONTENT_WIDTH - 20f)
        val height = 16f + lines.size * 12f
        ensureSpace(height + 5f)
        canvas.drawRoundRect(RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + height), 6f, 6f, fill(COLOR_NOTE))
        drawLines(lines, MARGIN + 10f, y + 15f, notePaint, 12f)
        y += height + 5f
    }

    //MARK:绘制单体表
    //cellTable 将单体按表头和数据行绘制到报告，跨页时重新绘制表头。
    fun cellTable(cells: List<CellHealthDiagnosis>) {
        val headers = listOf("单体", "基准mV", "最新mV", "绝对变化", "相对变化", "判断")
        val widths = listOf(42f, 78f, 78f, 80f, 80f, CONTENT_WIDTH - 358f)
        drawTableHeader(headers, widths)
        cells.forEach { cell ->
            if (ensureSpace(25f)) drawTableHeader(headers, widths)
            val values = listOf(
                "${cell.cellNumber}串", cell.baselineMv.toString(), cell.latestMv.toString(),
                signed(cell.absoluteChangeMv.toDouble()) + "mV",
                signed(cell.relativeDriftMv) + "mV",
                levelLabel(cell.level)
            )
            drawTableRow(values, widths, levelColor(cell.level))
        }
    }

    //MARK:绘制当前单体
    //currentCellTable 将当前单体按表头和数据行绘制到报告，跨页时重新绘制表头。
    fun currentCellTable(voltages: List<Int>) {
        val headers = listOf("单体", "当前电压mV", "相对平均mV", "说明")
        val widths = listOf(60f, 115f, 115f, CONTENT_WIDTH - 290f)
        val average = voltages.average()
        drawTableHeader(headers, widths)
        voltages.forEachIndexed { index, voltage ->
            if (ensureSpace(25f)) drawTableHeader(headers, widths)
            drawTableRow(
                listOf("${index + 1}串", voltage.toString(), signed(voltage - average), "缺少长期基准"),
                widths,
                COLOR_MUTED
            )
        }
    }

    //MARK:绘制容量表
    //capacityTable 将容量按表头和数据行绘制到报告，跨页时重新绘制表头。
    fun capacityTable(records: List<CapacityHealthRecord>) {
        if (records.isEmpty()) {
            emptyMessage("暂无容量测试记录。")
            return
        }
        val headers = listOf("日期", "实测Ah", "基准Ah", "SOH", "循环", "温度")
        val widths = listOf(105f, 76f, 76f, 70f, 70f, CONTENT_WIDTH - 397f)
        drawTableHeader(headers, widths)
        records.forEach { record ->
            if (ensureSpace(25f)) drawTableHeader(headers, widths)
            drawTableRow(
                listOf(
                    formatDate(record.recordedAtMillis), number(record.measuredDischargeAh),
                    number(record.ratedCapacityAh), "${number(record.sohPercent, 1)}%",
                    record.cycleCount?.toString() ?: "--",
                    record.averageTemperatureC?.let { "${number(it, 1)}℃" } ?: "--"
                ),
                widths,
                levelColor(capacityLevel(record.sohPercent))
            )
        }
    }

    //MARK:绘制保护事件
    //protectionEvents 将保护按表头和数据行绘制到报告，跨页时重新绘制表头。
    fun protectionEvents(events: List<ProtectionEvent>) {
        if (events.isEmpty()) {
            emptyMessage("未记录到BMS保护事件。")
            return
        }
        events.forEach { event ->
            val detail = "${formatDateTime(event.startedAtMillis)} · SOC ${event.stateOfChargePercent}% · " +
                "${number(event.totalVoltageV, 2)}V · ${number(event.currentA, 1)}A\n${event.summary}"
            val detailPaint = configuredPaint(8.5f, COLOR_MUTED)
            val lines = wrapLines(detail, detailPaint, CONTENT_WIDTH - 26f).take(4)
            val height = 27f + lines.size * 11f
            ensureSpace(height + 5f)
            canvas.drawRoundRect(RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + height), 6f, 6f, fill(COLOR_CARD))
            val color = when (event.severity.name) {
                "Critical" -> COLOR_ERROR
                "Warning" -> COLOR_WARNING
                else -> COLOR_PRIMARY
            }
            drawText(event.title, MARGIN + 10f, y + 17f, 10f, color, medium)
            drawLines(lines, MARGIN + 10f, y + 31f, detailPaint, 11f)
            y += height + 5f
        }
    }

    //MARK:绘制项目符号
    //bullet 绘制项目符号和自动换行正文，用于列出建议或诊断依据。
    fun bullet(value: String) {
        val bodyPaint = configuredPaint(9f, COLOR_TEXT)
        val lines = wrapLines(value, bodyPaint, CONTENT_WIDTH - 20f)
        val height = max(14f, lines.size * 12f)
        ensureSpace(height + 5f)
        canvas.drawCircle(MARGIN + 4f, y + 5f, 2.2f, fill(COLOR_PRIMARY))
        drawLines(lines, MARGIN + 13f, y + 8f, bodyPaint, 12f)
        y += height + 5f
    }

    //MARK:绘制空态提示
    //emptyMessage 在没有可用样本时绘制浅色占位框及原因说明。
    fun emptyMessage(value: String) {
        val lines = wrapLines(value, configuredPaint(10f, COLOR_MUTED), CONTENT_WIDTH - 20f)
        val height = 18f + lines.size * 13f
        ensureSpace(height)
        drawLines(lines, MARGIN + 10f, y + 14f, configuredPaint(10f, COLOR_MUTED), 13f)
        y += height
    }

    //MARK:输出PDF
    //writeTo 管理writeTo的文档输出或分页生命周期，确保页面和文件资源正确结束。
    fun writeTo(output: OutputStream) {
        finishPage()
        document.writeTo(output)
    }

    //MARK:关闭资源
    //close 管理close的文档输出或分页生命周期，确保页面和文件资源正确结束。
    fun close() = document.close()

    //MARK:绘制表头
    //drawTableHeader 绘制表格背景、列分隔和表头文字，供跨页表格重复调用。
    private fun drawTableHeader(headers: List<String>, widths: List<Float>) {
        ensureSpace(25f)
        var x = MARGIN
        headers.forEachIndexed { index, header ->
            canvas.drawRect(x, y, x + widths[index], y + 22f, fill(COLOR_TABLE_HEADER))
            drawCenteredText(header, x, y + 15f, widths[index], 8f, COLOR_TEXT, medium)
            x += widths[index]
        }
        y += 22f
    }

    //MARK:绘制表格行
    //drawTableRow 在 PDF 画布上绘制数据行，处理可用宽度、换行和页面边界。
    private fun drawTableRow(values: List<String>, widths: List<Float>, accent: Int) {
        var x = MARGIN
        values.forEachIndexed { index, value ->
            canvas.drawRect(x, y, x + widths[index], y + 23f, stroke(COLOR_LINE, 0.5f))
            drawFittedCenteredText(value, x, y + 15f, widths[index], 8f, if (index == values.lastIndex) accent else COLOR_TEXT)
            x += widths[index]
        }
        y += 23f
    }

    //MARK:检查页面空间
    //ensureSpace 管理ensureSpace的文档输出或分页生命周期，确保页面和文件资源正确结束。
    private fun ensureSpace(required: Float): Boolean {
        if (y + required <= CONTENT_BOTTOM) return false
        startPage()
        return true
    }

    //MARK:开始PDF页
    //startPage 管理页面的文档输出或分页生命周期，确保页面和文件资源正确结束。
    private fun startPage() {
        finishPage()
        pageNumber += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create())
        canvas = requireNotNull(page).canvas
        canvas.drawColor(Color.WHITE)
        drawText("电动 BMS", MARGIN, 28f, 10f, COLOR_PRIMARY, medium)
        drawText("电池健康诊断报告", PAGE_WIDTH - MARGIN - 90f, 28f, 8f, COLOR_MUTED)
        canvas.drawLine(MARGIN, 36f, PAGE_WIDTH - MARGIN, 36f, stroke(COLOR_LINE, 0.8f))
        y = CONTENT_TOP
    }

    //MARK:结束PDF页
    //finishPage 管理页面的文档输出或分页生命周期，确保页面和文件资源正确结束。
    private fun finishPage() {
        val current = page ?: return
        canvas.drawLine(MARGIN, PAGE_HEIGHT - 35f, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 35f, stroke(COLOR_LINE, 0.7f))
        drawText("本地只读监测报告 · ${formatDate(generatedAtMillis)}", MARGIN, PAGE_HEIGHT - 20f, 7f, COLOR_MUTED)
        drawText("第${pageNumber}页", PAGE_WIDTH - MARGIN - 25f, PAGE_HEIGHT - 20f, 7f, COLOR_MUTED)
        document.finishPage(current)
        page = null
    }

    //MARK:绘制普通文字
    //text 绘制一段普通文本并按字号更新当前纵向位置。
    private fun text(value: String, size: Float, color: Int, typeface: Typeface = regular) {
        val textPaint = configuredPaint(size, color, typeface)
        val lines = wrapLines(value, textPaint, CONTENT_WIDTH)
        ensureSpace(lines.size * (size + 4f))
        drawLines(lines, MARGIN, y + size, textPaint, size + 4f)
        y += lines.size * (size + 4f)
    }

    //MARK:绘制多行文字
    //drawLines 逐行绘制已经完成换行的文本，并按行高推进基线。
    private fun drawLines(lines: List<String>, x: Float, baseline: Float, textPaint: Paint, lineHeight: Float) {
        lines.forEachIndexed { index, line -> canvas.drawText(line, x, baseline + index * lineHeight, textPaint) }
    }

    //MARK:绘制文字
    //drawText 在指定坐标使用给定字号、颜色和字体绘制单行文字。
    private fun drawText(
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        typeface: Typeface = regular
    ) {
        canvas.drawText(value, x, baseline, configuredPaint(size, color, typeface))
    }

    //MARK:绘制适配文字
    //drawFittedText 逐步缩小字号直到文字适合指定宽度，再绘制左对齐文本。
    private fun drawFittedText(
        value: String,
        x: Float,
        baseline: Float,
        maximumWidth: Float,
        initialSize: Float,
        color: Int,
        typeface: Typeface = regular
    ) {
        val fitted = fittedPaint(value, maximumWidth, initialSize, color, typeface)
        canvas.drawText(value, x, baseline, fitted)
    }

    //MARK:绘制居中文字
    //drawCenteredText 按测量宽度计算起点，在指定中心位置绘制单行文字。
    private fun drawCenteredText(
        value: String,
        x: Float,
        baseline: Float,
        width: Float,
        size: Float,
        color: Int,
        typeface: Typeface = regular
    ) {
        val textPaint = configuredPaint(size, color, typeface)
        canvas.drawText(value, x + (width - textPaint.measureText(value)) / 2f, baseline, textPaint)
    }

    //MARK:适配居中文字
    //drawFittedCenteredText 先按可用宽度缩放字体，再将结果居中绘制。
    private fun drawFittedCenteredText(value: String, x: Float, baseline: Float, width: Float, size: Float, color: Int) {
        val textPaint = fittedPaint(value, width - 5f, size, color, regular)
        canvas.drawText(value, x + (width - textPaint.measureText(value)) / 2f, baseline, textPaint)
    }

    //MARK:计算适配画笔
    //fittedPaint 从目标字号开始缩小 Paint，直到文本宽度不超过限制或达到最小字号。
    private fun fittedPaint(value: String, width: Float, initialSize: Float, color: Int, typeface: Typeface): Paint {
        val result = configuredPaint(initialSize, color, typeface)
        while (result.textSize > 6f && result.measureText(value) > width) result.textSize -= 0.5f
        return result
    }

    //MARK:配置文字画笔
    //configuredPaint 创建包含字号、颜色和字体的抗锯齿 Paint，避免修改共享画笔污染其他内容。
    private fun configuredPaint(size: Float, color: Int, typeface: Typeface = regular): Paint = Paint(paint).apply {
        style = Paint.Style.FILL
        textSize = size
        this.color = color
        this.typeface = typeface
    }

    //MARK:创建填充画笔
    //fill 创建填充样式画笔，用于卡片背景、圆点和色块。
    private fun fill(color: Int): Paint = Paint(paint).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    //MARK:创建描边画笔
    //stroke 创建指定颜色和线宽的描边画笔，用于网格、边框和趋势线。
    private fun stroke(color: Int, width: Float): Paint = Paint(paint).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }

    //MARK:拆分文本行
    //wrapLines 按实际文字宽度和换行符拆分文本，保证每行都不超出 PDF 内容区。
    private fun wrapLines(value: String, textPaint: Paint, width: Float): List<String> {
        if (value.isEmpty()) return listOf("")
        return buildList {
            value.split('\n').forEach { paragraph ->
                if (paragraph.isEmpty()) {
                    add("")
                } else {
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = textPaint.breakText(remaining, true, width, null).coerceAtLeast(1)
                        var end = min(count, remaining.length)
                        if (end < remaining.length) {
                            val space = remaining.substring(0, end).lastIndexOf(' ')
                            if (space > end / 2) end = space
                        }
                        add(remaining.substring(0, end).trim())
                        remaining = remaining.substring(end).trimStart()
                    }
                }
            }
        }
    }

    //MARK:常量配置
    //定义 A4 页面尺寸、内容边距、颜色和日期格式，所有报告区块共享这些排版基准。
    companion object {
        private const val PAGE_WIDTH = 595f
        private const val PAGE_HEIGHT = 842f
        private const val MARGIN = 42f
        private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2
        private const val CONTENT_TOP = 53f
        private const val CONTENT_BOTTOM = PAGE_HEIGHT - 45f
        private const val COLOR_TEXT = 0xFF17332D.toInt()
        private const val COLOR_MUTED = 0xFF637A74.toInt()
        private const val COLOR_PRIMARY = 0xFF159A82.toInt()
        private const val COLOR_OBSERVE = 0xFFE1A800.toInt()
        private const val COLOR_WARNING = 0xFFE56F2D.toInt()
        private const val COLOR_ERROR = 0xFFC83C3C.toInt()
        private const val COLOR_CARD = 0xFFF1F6F4.toInt()
        private const val COLOR_NOTE = 0xFFF7F8F2.toInt()
        private const val COLOR_TABLE_HEADER = 0xFFDDECE7.toInt()
        private const val COLOR_LINE = 0xFFCAD8D4.toInt()
    }
}

//MARK:报告等级颜色
//levelColor 把诊断等级映射为报告中的正常、提醒或危险颜色。
private fun levelColor(level: HealthDiagnosisLevel): Int = when (level) {
    HealthDiagnosisLevel.Insufficient -> 0xFF637A74.toInt()
    HealthDiagnosisLevel.Normal -> 0xFF159A82.toInt()
    HealthDiagnosisLevel.Observe -> 0xFFE1A800.toInt()
    HealthDiagnosisLevel.Warning -> 0xFFE56F2D.toInt()
    HealthDiagnosisLevel.Critical -> 0xFFC83C3C.toInt()
}

//MARK:风险等级文字
//levelLabel 把诊断等级转换为用户可读的中文结论。
private fun levelLabel(level: HealthDiagnosisLevel): String = when (level) {
    HealthDiagnosisLevel.Insufficient -> "数据不足"
    HealthDiagnosisLevel.Normal -> "正常"
    HealthDiagnosisLevel.Observe -> "观察"
    HealthDiagnosisLevel.Warning -> "警告"
    HealthDiagnosisLevel.Critical -> "严重"
}

//MARK:置信度文字
//confidenceLabel 把诊断置信度转换为高、中、低中文说明。
private fun confidenceLabel(confidence: HealthDiagnosisConfidence): String = when (confidence) {
    HealthDiagnosisConfidence.Low -> "低"
    HealthDiagnosisConfidence.Medium -> "中"
    HealthDiagnosisConfidence.High -> "高"
}

//MARK:判断容量等级
//capacityLevel 根据实测 SOH 所在区间返回容量健康等级。
private fun capacityLevel(soh: Double): HealthDiagnosisLevel = when {
    soh < 75.0 -> HealthDiagnosisLevel.Critical
    soh < 80.0 -> HealthDiagnosisLevel.Warning
    soh < 90.0 -> HealthDiagnosisLevel.Observe
    else -> HealthDiagnosisLevel.Normal
}

//MARK:格式化数字
//number 按指定小数位格式化可空数值，缺失数据统一显示为短横线。
private fun number(value: Double, decimals: Int = 1): String {
    val formatted = String.format(Locale.US, "%.${decimals}f", value)
    return if (decimals == 0) formatted else formatted.trimEnd('0').trimEnd('.')
}

//MARK:格式化符号
//signed 格式化带正负方向的变化值，并为正数显式添加加号。
private fun signed(value: Double): String = "${if (value > 0) "+" else ""}${number(value, 0)}"

//MARK:混合浅色
//blendWithWhite 按给定比例混合目标颜色与白色，生成报告使用的浅色背景。
private fun blendWithWhite(color: Int, whiteRatio: Float): Int {
    val ratio = whiteRatio.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(color) * (1f - ratio) + 255 * ratio).toInt(),
        (Color.green(color) * (1f - ratio) + 255 * ratio).toInt(),
        (Color.blue(color) * (1f - ratio) + 255 * ratio).toInt()
    )
}

private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val shortDateFormatter = DateTimeFormatter.ofPattern("M/d")

//MARK:格式化时间
//formatDateTime 把时间戳转换为本地时区的完整日期和时间。
private fun formatDateTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(fullDateFormatter)

//MARK:格式化日期
//formatDate 把时间戳转换为报告标题使用的年月日。
private fun formatDate(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(dateFormatter)

//MARK:格式化短日期
//formatShortDate 把时间戳转换为图表横轴使用的简短月日。
private fun formatShortDate(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(shortDateFormatter)
