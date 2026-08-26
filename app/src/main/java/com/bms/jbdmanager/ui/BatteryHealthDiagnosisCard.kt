package com.bms.jbdmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BatteryHealthDiagnosis
import com.bms.jbdmanager.model.HealthDiagnosisConfidence
import com.bms.jbdmanager.model.HealthDiagnosisFinding
import com.bms.jbdmanager.model.HealthDiagnosisLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun BatteryHealthDiagnosisCard(diagnosis: BatteryHealthDiagnosis) {
    val accent = diagnosisColor(diagnosis.overallLevel)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = if (diagnosis.overallLevel == HealthDiagnosisLevel.Insufficient) 0.10f else 0.14f)
        ),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("电池健康诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        diagnosis.summary,
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(
                        "可信度 ${confidenceLabel(diagnosis.confidence)}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "依据：${diagnosis.capacityTestCount}次容量测试 · ${diagnosis.comparableFingerprintCount}次可比满充记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                "单体比较条件：SOC差≤2%、最高温差≤7℃、串数相同且间隔至少7天",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
            Spacer(Modifier.height(7.dp))
            diagnosis.findings.forEach { finding ->
                DiagnosisFindingRow(finding)
                Spacer(Modifier.height(6.dp))
            }

            if (diagnosis.cells.isNotEmpty()) {
                val baseline = diagnosis.baselineFingerprint
                val latest = diagnosis.latestFingerprint
                Spacer(Modifier.height(2.dp))
                Text("逐串长期变化", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (baseline != null && latest != null) {
                    Text(
                        "${diagnosisDate(baseline.capturedAtMillis)} → ${diagnosisDate(latest.capturedAtMillis)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(7.dp))
                diagnosis.cells.sortedBy { it.cellNumber }.chunked(4).forEach { rowCells ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        rowCells.forEach { cell ->
                            val cellColor = diagnosisColor(cell.level)
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("${cell.cellNumber}串", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "${formatDiagnosisVoltage(cell.latestMv)}V",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "相对 ${signedDiagnosis(cell.relativeDriftMv)}mV",
                                        color = cellColor,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "绝对 ${if (cell.absoluteChangeMv > 0) "+" else ""}${cell.absoluteChangeMv}mV",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                        repeat(4 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(5.dp))
                }
                Text(
                    "“相对变化”已减去整组电压共同升降，可降低充电截止电压和温度变化造成的误判。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

@Composable
private fun DiagnosisFindingRow(finding: HealthDiagnosisFinding) {
    val color = diagnosisColor(finding.level)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(finding.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                finding.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun diagnosisColor(level: HealthDiagnosisLevel): Color = when (level) {
    HealthDiagnosisLevel.Insufficient -> MaterialTheme.colorScheme.onSurfaceVariant
    HealthDiagnosisLevel.Normal -> MaterialTheme.colorScheme.primary
    HealthDiagnosisLevel.Observe -> Color(0xFFFFC107)
    HealthDiagnosisLevel.Warning -> Color(0xFFFF8A3D)
    HealthDiagnosisLevel.Critical -> MaterialTheme.colorScheme.error
}

private fun confidenceLabel(confidence: HealthDiagnosisConfidence): String = when (confidence) {
    HealthDiagnosisConfidence.Low -> "低"
    HealthDiagnosisConfidence.Medium -> "中"
    HealthDiagnosisConfidence.High -> "高"
}

private fun formatDiagnosisVoltage(millivolts: Int): String =
    String.format(Locale.US, "%.3f", millivolts / 1_000.0)

private fun signedDiagnosis(value: Double): String =
    "${if (value > 0) "+" else ""}${String.format(Locale.US, "%.0f", value)}"

private val diagnosisDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun diagnosisDate(timestamp: Long): String = diagnosisDateFormatter.format(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
)
