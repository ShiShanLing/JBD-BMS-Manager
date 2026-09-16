package com.bms.jbdmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.protocol.JbdAdminParameterSpec
import com.bms.jbdmanager.protocol.JbdAdminParameters
import java.util.Locale
import kotlin.math.abs

@Composable
//MARK:管理员页面
//AdminParametersPage 提供受控参数编辑、风险确认和工厂密码输入；只提交相对当前回读值真正变化的白名单字段。
internal fun AdminParametersPage(
    state: BmsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWrite: (String, Map<Int, Double>) -> Boolean,
    onClearResult: () -> Unit
) {
    val params = state.protectionParams
    // 工厂密码只保存在当前组合生命周期内，不写入 Bundle、磁盘或最后状态快照。
    var factoryPassword by remember { mutableStateOf("5678") }
    var acceptedRisk by rememberSaveable { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var edits: Map<Int, String> by remember(params) {
        mutableStateOf(
            if (params == null) emptyMap<Int, String>() else JbdAdminParameters.specs.associate { spec ->
                spec.register to (JbdAdminParameters.value(params, spec.register)?.let { formatAdminValue(it, spec.decimals) } ?: "")
            }
        )
    }
    val parsedChanges: Map<Int, Double> = if (params == null) emptyMap() else JbdAdminParameters.specs.mapNotNull { spec ->
        val parsed = edits[spec.register]?.toDoubleOrNull() ?: return@mapNotNull null
        val original = JbdAdminParameters.value(params, spec.register) ?: return@mapNotNull null
        if (abs(parsed - original) > 0.000_000_1) spec.register to parsed else null
    }.toMap()
    val invalidInput = params != null && JbdAdminParameters.specs.any { spec ->
        JbdAdminParameters.value(params, spec.register) != null && edits[spec.register]?.toDoubleOrNull() == null
    }

    LaunchedEffect(state.adminParameterWrite.succeeded) {
        if (state.adminParameterWrite.succeeded == true) acceptedRisk = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, enabled = !state.adminParameterWrite.inProgress) {
                Text("‹", fontSize = 32.sp, fontWeight = FontWeight.Light)
            }
            Column(Modifier.weight(1f)) {
                Text("管理员参数", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("JBD V12 工厂模式写入", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.adminParameterWrite.inProgress) { Text("重读") }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("高风险操作", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        Text(
                            "错误阈值可能导致电芯过充、过放、过热、保护失效甚至起火。写入时请停止骑行和充电，并保持手机靠近 BMS。页面不会开放采样电阻、串数、功能位等底层校准项。",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
            if (params == null) {
                item {
                    Text(state.protectionParamsError ?: "尚未读取到保护参数，请先点击重读", color = MaterialTheme.colorScheme.error)
                }
            } else {
                JbdAdminParameters.specs.groupBy { it.group }.forEach { (group, specs) ->
                    item { Text(group, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp)) }
                    items(specs, key = { it.register }) { spec ->
                        AdminParameterField(spec, edits[spec.register].orEmpty()) { value ->
                            edits = edits + (spec.register to value.filter { it.isDigit() || it == '.' || it == '-' })
                            onClearResult()
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = factoryPassword,
                        onValueChange = { factoryPassword = it.uppercase().filter { char -> char.isDigit() || char in 'A'..'F' }.take(4) },
                        label = { Text("4位工厂密码") },
                        supportingText = { Text("默认通常为 5678；它不同于6位蓝牙读取密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        enabled = !state.adminParameterWrite.inProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acceptedRisk, onCheckedChange = { acceptedRisk = it }, enabled = !state.adminParameterWrite.inProgress)
                        Text("我已核对参数并理解错误设置可能损坏电池", fontSize = 12.sp)
                    }
                }
                state.adminParameterWrite.message?.let { message ->
                    item {
                        Text(
                            message,
                            color = when (state.adminParameterWrite.succeeded) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.secondary
                            },
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
                item {
                    Button(
                        onClick = { showConfirmation = true },
                        enabled = acceptedRisk && factoryPassword.length == 4 && parsedChanges.isNotEmpty() && !invalidInput && !state.adminParameterWrite.inProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.adminParameterWrite.inProgress) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(if (state.adminParameterWrite.inProgress) "正在安全写入" else "写入 ${parsedChanges.size} 项修改")
                    }
                }
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("最后确认写入") },
            text = { Text("将进入 BMS 工厂模式并修改 ${parsedChanges.size} 项参数。写入完成后 App 会退出工厂模式并回读校验。过程中不要断开蓝牙、关闭 App、充电或骑行。") },
            confirmButton = {
                Button(onClick = {
                    showConfirmation = false
                    onWrite(factoryPassword, parsedChanges)
                }) { Text("确认写入") }
            },
            dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("取消") } }
        )
    }
}

@Composable
//MARK:管理员输入项
//AdminParameterField 展示单个白名单参数的当前可编辑值、单位和允许范围。
private fun AdminParameterField(spec: JbdAdminParameterSpec, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(spec.label) },
        suffix = { Text(spec.unit) },
        supportingText = { Text("允许 ${formatAdminValue(spec.minimum, spec.decimals)}–${formatAdminValue(spec.maximum, spec.decimals)} ${spec.unit} · 寄存器 ${spec.register}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

//MARK:格式化管理员值
//formatAdminValue 按协议字段精度生成稳定文本，同时移除无意义的尾随零。
private fun formatAdminValue(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
