package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.model.BmsBasicInfo
import com.bms.jbdmanager.model.CellSummary
import com.bms.jbdmanager.model.JbdProtectionParams
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

data class JbdFrame(
    val command: Int,
    val status: Int,
    val data: ByteArray,
    val raw: ByteArray
)

sealed interface JbdMessage {
    data class BasicInfo(val value: BmsBasicInfo) : JbdMessage
    data class Cells(val value: CellSummary) : JbdMessage
    data class HardwareVersion(val value: String) : JbdMessage
    data class ChipType(val value: String) : JbdMessage
    data class ProtectionParams(val value: JbdProtectionParams) : JbdMessage
    data class Unsupported(val command: Int, val status: Int) : JbdMessage
    data class Unknown(val command: Int, val data: ByteArray) : JbdMessage
}

object JbdProtocol {
    const val BASIC_INFO = 0x03
    const val CELL_VOLTAGES = 0x04
    const val HARDWARE_VERSION = 0x05
    const val PASSWORD_PAIRING = 0x06
    const val CHIP_TYPE = 0x00
    const val READ_PARAMETERS = 0xFA

    fun readCommand(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= 255)
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

    fun readParametersCommand(startRegister: Int, count: Int): ByteArray {
        require(startRegister in 0..255)
        require(count in 1..95)
        return readCommand(
            READ_PARAMETERS,
            byteArrayOf((startRegister shr 8).toByte(), startRegister.toByte(), count.toByte())
        )
    }

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

    fun decode(raw: ByteArray): Result<JbdFrame> = runCatching {
        require(raw.size >= 7) { "报文长度不足" }
        require(raw.first().u8() == 0xDD) { "帧头错误" }
        require(raw.last().u8() == 0x77) { "帧尾错误" }
        val length = raw[3].u8()
        require(raw.size == length + 7) { "长度字段不匹配" }

        val expected = ((raw[raw.lastIndex - 2].u8() shl 8) or raw[raw.lastIndex - 1].u8())
        val sum = (2 until 4 + length).sumOf { raw[it].u8() }
        val actual = (-sum) and 0xFFFF
        require(expected == actual) { "校验失败" }

        JbdFrame(
            command = raw[1].u8(),
            status = raw[2].u8(),
            data = raw.copyOfRange(4, 4 + length),
            raw = raw
        )
    }

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

    private fun parseBasicInfo(data: ByteArray): BmsBasicInfo {
        require(data.size >= 23) { "基本信息字段不足" }
        val fetState = data[20].u8()
        val highCapacityUnit = fetState and 0x80 != 0
        val capacityScaleAh = if (highCapacityUnit) 0.1 else 0.01
        val currentScaleA = if (highCapacityUnit) 0.1 else 0.01
        val ntcCount = data[22].u8().coerceAtMost(16)
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
        // Some V12 frames repeat remaining capacity after FCC; skip that duplicate field.
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

    private fun parseProtectionParams(data: ByteArray): JbdProtectionParams {
        require(data.size >= 3) { "保护参数字段不足" }
        val start = data.u16(0)
        val count = data[2].u8()
        require(data.size >= 3 + count * 2) { "保护参数长度不匹配" }
        fun raw(param: Int): Int? {
            val index = param - start
            if (index !in 0 until count) return null
            return data.u16(3 + index * 2)
        }
        fun milliVolts(param: Int) = raw(param)?.div(1000.0)
        fun packVolts(param: Int) = raw(param)?.times(0.01)
        fun tempC(param: Int) = raw(param)?.minus(2731)?.div(10.0)
        fun chargeAmps(param: Int) = raw(param)?.times(0.01)
        fun dischargeAmps(param: Int) = raw(param)?.let { value ->
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

    private fun parseCells(data: ByteArray): CellSummary {
        require(data.isNotEmpty() && data.size % 2 == 0) { "单体电压字段长度错误" }
        return CellSummary(List(data.size / 2) { index -> data.u16(index * 2) })
    }

    private fun parseHardwareVersion(data: ByteArray): String {
        val cleaned = data.takeWhile { it.toInt() != 0 }.toByteArray()
        return String(cleaned, StandardCharsets.US_ASCII)
            .filter { it.code in 32..126 }
            .trim()
            .ifBlank { "未知型号" }
    }

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

    private fun checksumForRequest(command: Int, data: ByteArray): Int {
        val sum = command + data.size + data.sumOf { it.u8() }
        return (-sum) and 0xFFFF
    }

    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.u8()) }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].u8() shl 8) or this[offset + 1].u8()

    private fun ByteArray.s16(offset: Int): Int {
        val raw = u16(offset)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }
}
