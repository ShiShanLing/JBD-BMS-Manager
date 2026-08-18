package com.bms.jbdmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.model.BrakeTestPhase


@Composable
internal fun BrakeTestPage(
    state: BmsUiState,
    onArmBrakeTest: (Int) -> Unit,
    onCancelBrakeTest: () -> Unit
) {
    val test = state.trip.brakeTest
    var selectedSpeed by rememberSaveable { mutableIntStateOf(test.targetSpeedKmh) }
    val presets = listOf(25, 30, 35, 40, 45, 50, 55, 60, 65)
    val phaseText = when (test.phase) {
        BrakeTestPhase.Idle -> "未开始"
        BrakeTestPhase.Armed -> "等待达到目标速度"
        BrakeTestPhase.Ready -> "已就绪"
        BrakeTestPhase.Braking -> "制动中"
        BrakeTestPhase.Complete -> "测试完成"
        BrakeTestPhase.Failed -> "测试无效"
    }
    val phaseColor = when (test.phase) {
        BrakeTestPhase.Ready, BrakeTestPhase.Complete -> MaterialTheme.colorScheme.primary
        BrakeTestPhase.Armed, BrakeTestPhase.Braking -> MaterialTheme.colorScheme.secondary
        BrakeTestPhase.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("刹车距离测试", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(phaseText, color = phaseColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            compactNumber(test.currentSpeedKmh, 1),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            fontSize = 30.sp,
                            lineHeight = 32.sp
                        )
                        Text(" km/h", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text(test.message, color = phaseColor, fontSize = 12.sp, lineHeight = 15.sp)
                }
            }
        }
        item {
            Text("选择起始速度", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                presets.forEach { speed ->
                    val selected = selectedSpeed == speed
                    Surface(
                        modifier = Modifier.weight(1f).clickable {
                            if (!test.isRunning) selectedSpeed = speed
                        },
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "$speed",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 7.dp)
                        )
                    }
                }
            }
        }
        item {
            if (test.isRunning) {
                OutlinedButton(onClick = onCancelBrakeTest, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("取消本次测试")
                }
            } else {
                Button(
                    onClick = { onArmBrakeTest(selectedSpeed) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("开始测试 · $selectedSpeed km/h", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            MetricRow(
                Metric("刹车距离", if (test.phase == BrakeTestPhase.Complete) format(test.brakingDistanceMeters, "m") else "--", "速度积分估算"),
                Metric("刹车时间", if (test.phase == BrakeTestPhase.Complete) format(test.brakingDurationSeconds, "s") else "--", "目标速度至停止")
            )
        }
        item {
            MetricRow(
                Metric("平均减速度", test.averageDecelerationMps2?.let { format(it, "m/s²") } ?: "--", "制动强度"),
                Metric("GPS 采样", "${compactNumber(test.sampleRateHz, 1)} Hz", "可信度：${test.confidence}")
            )
        }
        item {
            Text(
                "请只在封闭、空旷、无行人车辆的安全路段测试，并将手机牢固固定。手机 GPS 实际采样频率由硬件决定，结果仅用于个人对比。",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp)
            )
        }
    }
}
