package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.model.JbdProtectionParams
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

//MARK:参数定义
//JbdAdminParameterSpec 描述一个允许修改的安全参数及其按钮步长；寄存器范围之外的底层校准项不会出现在管理员页面。
data class JbdAdminParameterSpec(
    val register: Int,
    val group: String,
    val label: String,
    val unit: String,
    val minimum: Double,
    val maximum: Double,
    val decimals: Int,
    val step: Double
)

//MARK:参数编解码
//JbdAdminParameters 集中维护管理员页面的白名单、单位换算和成对阈值校验，防止界面直接拼装危险寄存器值。
object JbdAdminParameters {
    val specs = listOf(
        JbdAdminParameterSpec(0, "容量与均衡", "标称容量", "Ah", 1.0, 650.0, 2, 0.1),
        JbdAdminParameterSpec(2, "容量与均衡", "充满单体电压", "V", 2.5, 4.5, 3, 0.005),
        JbdAdminParameterSpec(26, "容量与均衡", "均衡启动电压", "V", 2.0, 4.5, 3, 0.005),
        JbdAdminParameterSpec(27, "容量与均衡", "均衡启动压差", "V", 0.001, 0.200, 3, 0.001),
        JbdAdminParameterSpec(20, "单体电压", "过充保护", "V", 2.5, 4.5, 3, 0.005),
        JbdAdminParameterSpec(21, "单体电压", "过充恢复", "V", 2.5, 4.5, 3, 0.005),
        JbdAdminParameterSpec(22, "单体电压", "欠压保护", "V", 1.5, 3.6, 3, 0.005),
        JbdAdminParameterSpec(23, "单体电压", "欠压恢复", "V", 1.5, 3.8, 3, 0.005),
        JbdAdminParameterSpec(16, "整包电压", "过压保护", "V", 10.0, 120.0, 2, 0.1),
        JbdAdminParameterSpec(17, "整包电压", "过压恢复", "V", 10.0, 120.0, 2, 0.1),
        JbdAdminParameterSpec(18, "整包电压", "欠压保护", "V", 10.0, 120.0, 2, 0.1),
        JbdAdminParameterSpec(19, "整包电压", "欠压恢复", "V", 10.0, 120.0, 2, 0.1),
        JbdAdminParameterSpec(24, "过流", "充电过流", "A", 0.1, 300.0, 1, 1.0),
        JbdAdminParameterSpec(25, "过流", "放电过流", "A", 0.1, 500.0, 1, 1.0),
        JbdAdminParameterSpec(8, "充电温度", "高温保护", "℃", -40.0, 120.0, 1, 1.0),
        JbdAdminParameterSpec(9, "充电温度", "高温恢复", "℃", -40.0, 120.0, 1, 1.0),
        JbdAdminParameterSpec(10, "充电温度", "低温保护", "℃", -40.0, 60.0, 1, 1.0),
        JbdAdminParameterSpec(11, "充电温度", "低温恢复", "℃", -40.0, 60.0, 1, 1.0),
        JbdAdminParameterSpec(12, "放电温度", "高温保护", "℃", -40.0, 120.0, 1, 1.0),
        JbdAdminParameterSpec(13, "放电温度", "高温恢复", "℃", -40.0, 120.0, 1, 1.0),
        JbdAdminParameterSpec(14, "放电温度", "低温保护", "℃", -40.0, 60.0, 1, 1.0),
        JbdAdminParameterSpec(15, "放电温度", "低温恢复", "℃", -40.0, 60.0, 1, 1.0)
    )

    //MARK:按钮调节
    //adjustedValue 按字段规定的固定步长增减参数，对结果按协议精度取整，并在安全范围边界处停止。
    fun adjustedValue(spec: JbdAdminParameterSpec, current: Double, direction: Int): Double {
        require(direction == -1 || direction == 1) { "调节方向只能为 -1 或 1" }
        val scale = 10.0.pow(spec.decimals)
        return (round((current + spec.step * direction) * scale) / scale).coerceIn(spec.minimum, spec.maximum)
    }

    //MARK:读取页面值
    //value 从已解析保护参数取得页面值；没有被本次参数块覆盖时返回空值并禁止用户盲写。
    fun value(params: JbdProtectionParams, register: Int): Double? = when (register) {
        0 -> params.nominalCapacityAh
        2 -> params.fullChargeVoltageV
        8 -> params.chargeHighTempC
        9 -> params.chargeHighTempReleaseC
        10 -> params.chargeLowTempC
        11 -> params.chargeLowTempReleaseC
        12 -> params.dischargeHighTempC
        13 -> params.dischargeHighTempReleaseC
        14 -> params.dischargeLowTempC
        15 -> params.dischargeLowTempReleaseC
        16 -> params.packOvervoltageV
        17 -> params.packOvervoltageReleaseV
        18 -> params.packUndervoltageV
        19 -> params.packUndervoltageReleaseV
        20 -> params.cellOvervoltageV
        21 -> params.cellOvervoltageReleaseV
        22 -> params.cellUndervoltageV
        23 -> params.cellUndervoltageReleaseV
        24 -> params.chargeOvercurrentA
        25 -> params.dischargeOvercurrentA
        26 -> params.balanceStartVoltageV
        27 -> params.balanceStartDeltaV
        else -> null
    }

    //MARK:校验并编码
    //validateAndEncode 检查白名单、单项范围和保护/恢复关系，再按当前板卡电流单位编码为原始寄存器值。
    fun validateAndEncode(params: JbdProtectionParams, changedValues: Map<Int, Double>): Result<Map<Int, Int>> = runCatching {
        require(changedValues.isNotEmpty()) { "没有需要写入的修改" }
        val specsByRegister = specs.associateBy { it.register }
        changedValues.forEach { (register, value) ->
            val spec = requireNotNull(specsByRegister[register]) { "寄存器 $register 不在安全写入白名单" }
            require(value.isFinite() && value in spec.minimum..spec.maximum) { "${spec.label}必须在 ${spec.minimum}–${spec.maximum}${spec.unit}" }
        }
        fun finalValue(register: Int) = changedValues[register] ?: value(params, register)
        requirePair(finalValue(20), finalValue(21), true, "单体过充保护必须高于或等于恢复电压")
        requirePair(finalValue(22), finalValue(23), false, "单体欠压保护必须低于或等于恢复电压")
        requirePair(finalValue(16), finalValue(17), true, "整包过压保护必须高于或等于恢复电压")
        requirePair(finalValue(18), finalValue(19), false, "整包欠压保护必须低于或等于恢复电压")
        requirePair(finalValue(8), finalValue(9), true, "充电高温保护必须高于恢复温度")
        requirePair(finalValue(10), finalValue(11), false, "充电低温保护必须低于恢复温度")
        requirePair(finalValue(12), finalValue(13), true, "放电高温保护必须高于恢复温度")
        requirePair(finalValue(14), finalValue(15), false, "放电低温保护必须低于恢复温度")

        val largeCapacityUnit = (params.rawRegisters[29] ?: 0) and 0x1000 != 0
        val currentScale = if (largeCapacityUnit) 10.0 else 100.0
        val capacityScale = if (largeCapacityUnit) 10.0 else 100.0
        changedValues.mapValues { (register, value) ->
            when (register) {
                0 -> (value * capacityScale).roundToInt()
                2, 20, 21, 22, 23, 26, 27 -> (value * 1000.0).roundToInt()
                in 8..15 -> (value * 10.0 + 2731.0).roundToInt()
                in 16..19 -> (value * 100.0).roundToInt()
                24 -> (value * currentScale).roundToInt()
                25 -> (0x10000 - (value * currentScale).roundToInt()) and 0xFFFF
                else -> error("寄存器 $register 不支持写入")
            }.also { require(it in 0..0xFFFF) { "${specsByRegister.getValue(register).label}超出协议范围" } }
        }
    }

    //MARK:校验阈值对
    //requirePair 对保护值和恢复值执行方向校验；任一值缺失时不阻止其他独立参数修改。
    private fun requirePair(first: Double?, second: Double?, greater: Boolean, message: String) {
        if (first == null || second == null) return
        require(if (greater) first >= second else first <= second) { message }
    }
}
