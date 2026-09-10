package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.JbdProtectionParams
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

//MARK:协议报文
//JbdFrame 将报文相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class JbdFrame(
    val command: Int,
    val status: Int,
    val data: ByteArray,
    val raw: ByteArray
)

//MARK:协议消息
//JbdMessage 定义JbdMessage的封闭结果类型，使调用方能够穷举处理每一种返回情况。
sealed interface JbdMessage {
    //MARK:基础状态消息
    //BasicInfo 汇总一次信息的计算或读取结果，调用方无需再从原始字段重复推导。
    data class BasicInfo(val value: BmsBasicInfo) : JbdMessage
    //MARK:单体消息
    //Cells 表示命令 0x04 解析成功后的全部单体电压及其统计摘要。
    data class Cells(val value: CellSummary) : JbdMessage
    //MARK:硬件版本消息
    //HardwareVersion 将版本相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class HardwareVersion(val value: String) : JbdMessage
    //MARK:芯片消息
    //ChipType 表示芯片方案字符串响应，供设备信息弹框展示。
    data class ChipType(val value: String) : JbdMessage
    //MARK:保护参数消息
    //ProtectionParams 将保护参数相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
    data class ProtectionParams(val value: JbdProtectionParams) : JbdMessage
    //MARK:不支持消息
    //Unsupported 保存 BMS 对某命令返回的非零状态码，使上层能够区分“不支持”和报文损坏。
    data class Unsupported(val command: Int, val status: Int) : JbdMessage
    //MARK:未知消息
    //Unknown 保留尚未适配命令的原始负载，避免解析器因为新增协议命令而失败。
    data class Unknown(val command: Int, val data: ByteArray) : JbdMessage
}

//MARK:JBD协议
//JbdProtocol 提供进程内共享的协议能力，并集中维护其状态、常量或纯计算入口。
object JbdProtocol {
    const val BASIC_INFO = 0x03
    const val CELL_VOLTAGES = 0x04
    const val HARDWARE_VERSION = 0x05
    const val PASSWORD_PAIRING = 0x06
    const val CHIP_TYPE = 0x00
    const val READ_PARAMETERS = 0xFA

    //MARK:构造读令
    //readCommand 按目标协议构造readCommand，包含规定的帧头、长度、负载和校验字段。
    fun readCommand(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= 255)
        // JBD 请求采用 16 位二补数校验，校验范围从命令字开始，不包含 DD/A5 帧头和 77 帧尾。
        val checksum = checksumForRequest(command, data)
        return byteArrayOf(
            0xDD.toByte(),
            0xA5.toByte(),
            command.toByte(),
            data.size.toByte(),
            *data,
            (checksum shr 8).toByte(),
            checksum.toByte(),
            0x77.toByte()
        )
    }

    //MARK:参数读令
    //readParametersCommand 按目标协议构造readParametersCommand，包含规定的帧头、长度、负载和校验字段。
    fun readParametersCommand(startRegister: Int, count: Int): ByteArray {
        require(startRegister in 0..255)
        require(count in 1..95)
        return readCommand(
            READ_PARAMETERS,
            byteArrayOf((startRegister shr 8).toByte(), startRegister.toByte(), count.toByte())
        )
    }

    //MARK:密码配对
    //passwordPairCommand 按目标协议构造passwordPairCommand，包含规定的帧头、长度、负载和校验字段。
    fun passwordPairCommand(password: String): Result<ByteArray> = runCatching {
        require(password.length == 6 && password.all(Char::isDigit)) { "蓝牙密码必须是6位数字" }
        val digits = password.map { (it - '0').toByte() }.toByteArray()
        val data = byteArrayOf(6, *digits)
        require(data.size <= 255)
        val checksum = checksumForRequest(PASSWORD_PAIRING, data)
        byteArrayOf(
            0xDD.toByte(),
            0x5A.toByte(),
            PASSWORD_PAIRING.toByte(),
            data.size.toByte(),
            *data,
            (checksum shr 8).toByte(),
            checksum.toByte(),
            0x77.toByte()
        )
    }

    //MARK:解码报文
    //decode 校验报文头尾、长度和校验和，通过后拆出命令、状态及负载字段。
    fun decode(raw: ByteArray): Result<JbdFrame> = runCatching {
        // 先检查固定边界，再相信长度字段读取数据，防止残包或错误长度造成越界和伪数据。
        require(raw.size >= 7) { "报文长度不足" }
        require(raw.first().u8() == 0xDD) { "帧头错误" }
        require(raw.last().u8() == 0x77) { "帧尾错误" }
        val length = raw[3].u8()
        require(raw.size == length + 7) { "长度字段不匹配" }

        val expected = ((raw[raw.lastIndex - 2].u8() shl 8) or raw[raw.lastIndex - 1].u8())
        val sum = (2 until 4 + length).sumOf { raw[it].u8() }
        val actual = (-sum) and 0xFFFF
        // 收到的校验值必须与状态字、长度及数据的二补数完全一致；失败报文不得进入业务解析。
        require(expected == actual) { "校验失败" }

        JbdFrame(
            command = raw[1].u8(),
            status = raw[2].u8(),
            data = raw.copyOfRange(4, 4 + length),
            raw = raw
        )
    }

    //MARK:解析报文
    //parse 解析输入内容并校验必要字段，将合法数据转换为对应的结构化结果。
    fun parse(frame: JbdFrame): Result<JbdMessage> = runCatching {
        if (frame.status != 0) {
            return@runCatching JbdMessage.Unsupported(frame.command, frame.status)
        }
        when (frame.command) {
            BASIC_INFO -> JbdMessage.BasicInfo(parseBasicInfo(frame.data))
            CELL_VOLTAGES -> JbdMessage.Cells(parseCells(frame.data))
            HARDWARE_VERSION -> JbdMessage.HardwareVersion(parseHardwareVersion(frame.data))
            CHIP_TYPE -> JbdMessage.ChipType(parseChipType(frame.data))
            READ_PARAMETERS -> JbdMessage.ProtectionParams(parseProtectionParams(frame.data))
            else -> JbdMessage.Unknown(frame.command, frame.data)
        }
    }

    //MARK:解析状态
    //parseBasicInfo 按 JBD 字段定义解析信息，在读取前检查长度并换算协议单位。
    private fun parseBasicInfo(data: ByteArray): BmsBasicInfo {
        require(data.size >= 23) { "基本信息字段不足" }
        val fetState = data[20].u8()
        // V12 扩展使用 FET 状态最高位声明大容量单位；容量和电流必须同步切换倍率。
        val highCapacityUnit = fetState and 0x80 != 0
        val capacityScaleAh = if (highCapacityUnit) 0.1 else 0.01
        val currentScaleA = if (highCapacityUnit) 0.1 else 0.01
        val ntcCount = data[22].u8().coerceAtMost(16)
        // 限制探头数量既符合常见 JBD 帧，也避免损坏报文声明超大数量后继续越界读取。
        require(data.size >= 23 + ntcCount * 2) { "温度字段长度不匹配" }

        val temperatures = List(ntcCount) { index ->
            (data.u16(23 + index * 2) - 2731) / 10.0
        }
        var extraOffset = 23 + ntcCount * 2
        val humidity = data.getOrNull(extraOffset)?.u8()
        if (humidity != null) extraOffset += 1
        val alarm = if (data.size >= extraOffset + 2) data.u16(extraOffset) else null
        if (alarm != null) extraOffset += 2
        val fullChargeCapacity = if (data.size >= extraOffset + 2) {
            data.u16(extraOffset) * capacityScaleAh
        } else null
        if (fullChargeCapacity != null) extraOffset += 2
        // V12 扩展在满充容量后会重复一次剩余容量；这里只跳过重复字段，不能把它误当作均衡电流。
        if (data.size >= extraOffset + 2) extraOffset += 2
        val balancingCurrent = if (data.size >= extraOffset + 2) data.s16(extraOffset) else null

        val dateRaw = data.u16(10)
        val year = 2000 + (dateRaw shr 9)
        val month = (dateRaw shr 5) and 0x0F
        val day = dateRaw and 0x1F
        val productionDate = if (month in 1..12 && day in 1..31) {
            "%04d-%02d-%02d".format(year, month, day)
        } else null

        val balanceLow = data.u16(12).toLong()
        val balanceHigh = data.u16(14).toLong()
        return BmsBasicInfo(
            totalVoltageV = data.u16(0) * 0.01,
            currentA = data.s16(2) * currentScaleA,
            remainingCapacityAh = data.u16(4) * capacityScaleAh,
            nominalCapacityAh = data.u16(6) * capacityScaleAh,
            fullChargeCapacityAh = fullChargeCapacity,
            stateOfChargePercent = data[19].u8().coerceIn(0, 100),
            cycleCount = data.u16(8),
            temperaturesC = temperatures,
            cellCount = data[21].u8(),
            chargeMosEnabled = fetState and 0x01 != 0,
            dischargeMosEnabled = fetState and 0x02 != 0,
            balancingMask = balanceLow or (balanceHigh shl 16),
            protectionMask = data.u16(16),
            alarmMask = alarm,
            softwareVersion = "${data[18].u8() shr 4}.${data[18].u8() and 0x0F}",
            productionDate = productionDate,
            humidityPercent = humidity,
            balancingCurrentMa = balancingCurrent
        )
    }

    //MARK:解析保护
    //parseProtectionParams 按 JBD 字段定义解析保护参数，在读取前检查长度并换算协议单位。
    private fun parseProtectionParams(data: ByteArray): JbdProtectionParams {
        require(data.size >= 3) { "保护参数字段不足" }
        val start = data.u16(0)
        val count = data[2].u8()
        require(data.size >= 3 + count * 2) { "保护参数长度不匹配" }
        //MARK:读取原始参数
        //把绝对寄存器号换算为本次响应中的相对位置；响应未覆盖该寄存器时返回空值。
        fun raw(param: Int): Int? {
            // 参数回复可从任意寄存器开始，先换算成相对下标；不在本次回复范围内的参数返回空值。
            val index = param - start
            if (index !in 0 until count) return null
            return data.u16(3 + index * 2)
        }
        //MARK:读取毫伏参数
        //读取以毫伏为单位的保护阈值，并换算成页面使用的伏特值。
        fun milliVolts(param: Int) = raw(param)?.div(1000.0)
        //MARK:读取总压参数
        //读取以 0.01V 为单位的电池包总压阈值，并换算为伏特。
        fun packVolts(param: Int) = raw(param)?.times(0.01)
        //MARK:读取温度参数
        //把协议中的 0.1K 温度值减去 273.1，换算为摄氏度。
        fun tempC(param: Int) = raw(param)?.minus(2731)?.div(10.0)
        //MARK:读取充电电流
        //读取以 0.01A 为单位的充电过流阈值并换算为安培。
        fun chargeAmps(param: Int) = raw(param)?.times(0.01)
        //MARK:读取放电电流
        //读取放电过流阈值；兼容 16 位补码编码，并返回页面使用的正幅值安培数。
        fun dischargeAmps(param: Int) = raw(param)?.let { value ->
            // 放电保护电流可能按 16 位补码传输；界面只需要阈值大小，因此在这里转换为正幅值。
            val magnitude = if (value and 0x8000 != 0) 0x10000 - value else value
            magnitude * 0.01
        }
        return JbdProtectionParams(
            fullChargeVoltageV = milliVolts(2),
            chargeHighTempC = tempC(8),
            chargeHighTempReleaseC = tempC(9),
            chargeLowTempC = tempC(10),
            chargeLowTempReleaseC = tempC(11),
            dischargeHighTempC = tempC(12),
            dischargeHighTempReleaseC = tempC(13),
            dischargeLowTempC = tempC(14),
            dischargeLowTempReleaseC = tempC(15),
            packOvervoltageV = packVolts(16),
            packOvervoltageReleaseV = packVolts(17),
            packUndervoltageV = packVolts(18),
            packUndervoltageReleaseV = packVolts(19),
            cellOvervoltageV = milliVolts(20),
            cellOvervoltageReleaseV = milliVolts(21),
            cellUndervoltageV = milliVolts(22),
            cellUndervoltageReleaseV = milliVolts(23),
            chargeOvercurrentA = chargeAmps(24),
            dischargeOvercurrentA = dischargeAmps(25)
        )
    }

    //MARK:解析单体
    //parseCells 按 JBD 字段定义解析parseCells，在读取前检查长度并换算协议单位。
    private fun parseCells(data: ByteArray): CellSummary {
        require(data.isNotEmpty() && data.size % 2 == 0) { "单体电压字段长度错误" }
        return CellSummary(List(data.size / 2) { index -> data.u16(index * 2) })
    }

    //MARK:解析型号
    //parseHardwareVersion 按 JBD 字段定义解析版本，在读取前检查长度并换算协议单位。
    private fun parseHardwareVersion(data: ByteArray): String {
        val cleaned = data.takeWhile { it.toInt() != 0 }.toByteArray()
        return String(cleaned, StandardCharsets.US_ASCII)
            .filter { it.code in 32..126 }
            .trim()
            .ifBlank { "未知型号" }
    }

    //MARK:解析芯片
    //parseChipType 按 JBD 字段定义解析parseChipType，在读取前检查长度并换算协议单位。
    private fun parseChipType(data: ByteArray): String {
        val value = data.lastOrNull()?.u8() ?: return "未知"
        return when (value) {
            0 -> "TI 方案"
            1 -> "凹凸方案"
            2 -> "芯唐松翰方案"
            3 -> "中颖 309"
            4 -> "中颖 303"
            5 -> "集澈方案"
            else -> "类型 $value"
        }
    }

    //MARK:计算校验
    //checksumForRequest 按目标协议构造checksumForRequest，包含规定的帧头、长度、负载和校验字段。
    private fun checksumForRequest(command: Int, data: ByteArray): Int {
        val sum = command + data.size + data.sumOf { it.u8() }
        return (-sum) and 0xFFFF
    }

    //MARK:转换十六进制
    //把字节数组格式化为空格分隔的大写十六进制文本，便于核对协议报文。
    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.u8()) }

    //MARK:读取U8
    //屏蔽 Kotlin Byte 的符号扩展，得到 0 到 255 的无符号数值。
    private fun Byte.u8(): Int = toInt() and 0xFF

    //MARK:读取U16
    //按 JBD 使用的大端字节序读取指定位置的 16 位无符号整数。
    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].u8() shl 8) or this[offset + 1].u8()

    //MARK:读取S16
    //先读取大端 16 位数，再按补码规则转换为有符号整数。
    private fun ByteArray.s16(offset: Int): Int {
        val raw = u16(offset)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }
}
