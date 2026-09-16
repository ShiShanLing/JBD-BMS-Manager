package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.protocol.JbdProtocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试协议
//JbdProtocolTest 验证 JbdProtocol 的正常流程、边界输入和需要长期保持的回归行为。
class JbdProtocolTest {
    @Test
    //MARK:测试协议
    //验证读取命令符合公开协议协议能够按预期解析字段、单位和边界值。
    fun readCommandsMatchPublishedProtocol() {
        assertEquals("DD A5 03 00 FF FD 77", JbdProtocol.readCommand(0x03).toHex())
        assertEquals("DD A5 04 00 FF FC 77", JbdProtocol.readCommand(0x04).toHex())
        assertEquals("DD A5 05 00 FF FB 77", JbdProtocol.readCommand(0x05).toHex())
        assertEquals("DD A5 FA 03 00 01 02 FF 00 77", JbdProtocol.readParametersCommand(1, 2).toHex())
    }

    @Test
    //MARK:测试写入报文
    //验证进入工厂模式、单寄存器写入和退出工厂模式均符合官方 V12 的 DD 5A 帧格式与校验规则。
    fun writeCommandsMatchPublishedProtocol() {
        assertEquals("DD 5A 00 02 56 78 FF 30 77", JbdProtocol.enterFactoryModeCommand("5678").getOrThrow().toHex())
        assertEquals("DD 5A FA 05 00 14 01 0E 42 FE 9C 77", JbdProtocol.writeParametersCommand(20, listOf(3650)).toHex())
        assertEquals("DD 5A 01 02 28 28 FF AD 77", JbdProtocol.exitFactoryModeCommand().toHex())
        assertTrue(JbdProtocol.enterFactoryModeCommand("12G4").isFailure)
    }

    @Test
    //MARK:测试参数报文
    //验证解析官方读取保护参数示例能够按预期解析字段、单位和边界值。
    fun parsesOfficialReadParametersExample() {
        val data = hex("00 01 02 0F A0 10 36")
        val message = JbdProtocol.parse(
            JbdProtocol.decode(response(0xFA, data)).getOrThrow()
        ).getOrThrow() as JbdMessage.ProtectionParams
        assertEquals(4.15, message.value.fullChargeVoltageV!!, 0.001)
    }

    @Test
    //MARK:测试保护参数
    //验证解析保护阈值from参数数据块能够按预期解析字段、单位和边界值。
    fun parsesProtectionThresholdsFromParameterBlock() {
        val data = hex(
            "00 14 06 0E 42 0D DE 09 C4 0A F0 13 88 D1 20"
        )
        val message = JbdProtocol.parse(
            JbdProtocol.decode(response(0xFA, data)).getOrThrow()
        ).getOrThrow() as JbdMessage.ProtectionParams
        assertEquals(3.65, message.value.cellOvervoltageV!!, 0.001)
        assertEquals(3.55, message.value.cellOvervoltageReleaseV!!, 0.001)
        assertEquals(2.50, message.value.cellUndervoltageV!!, 0.001)
        assertEquals(2.80, message.value.cellUndervoltageReleaseV!!, 0.001)
        assertEquals(50.0, message.value.chargeOvercurrentA!!, 0.001)
        assertEquals(120.0, message.value.dischargeOvercurrentA!!, 0.001)
    }

    @Test
    //MARK:测试协议
    //验证密码配对符合公开协议协议场景的关键输出，防止后续修改破坏既有行为。
    fun passwordPairingMatchesPublishedProtocol() {
        val command = JbdProtocol.passwordPairCommand("765828").getOrThrow()
        assertEquals("DD 5A 06 07 06 07 06 05 08 02 08 FF C9 77", command.toHex())
        assertTrue(JbdProtocol.passwordPairCommand("12345").isFailure)
        assertTrue(JbdProtocol.passwordPairCommand("12345A").isFailure)
    }

    @Test
    //MARK:测试分片组包
    //验证组帧器处理分片通知场景的关键输出，防止后续修改破坏既有行为。
    fun assemblerHandlesSplitNotifications() {
        val raw = hex("DD 05 00 03 4A 42 44 FF 2C 77")
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.append(raw.copyOfRange(0, 4)).isEmpty())
        val result = assembler.append(raw.copyOfRange(4, raw.size))
        assertEquals(1, result.size)
        assertEquals(raw.toList(), result.single().toList())
    }

    @Test
    //MARK:测试噪声组包
    //验证组帧器丢弃噪声and返回多帧报文场景的关键输出，防止后续修改破坏既有行为。
    fun assemblerDiscardsNoiseAndReturnsMultipleFrames() {
        val first = response(0x05, hex("4A 42 44"))
        val second = response(0x04, hex("0C E4 0C EE"))
        val assembler = JbdFrameAssembler()
        val frames = assembler.append(byteArrayOf(0x01, 0x02, *first, *second))
        assertEquals(2, frames.size)
        assertEquals(first.toList(), frames[0].toList())
        assertEquals(second.toList(), frames[1].toList())
    }

    @Test
    //MARK:测试损坏报文
    //构造解码拒绝损坏报文场景，确认实现会拒绝无效输入或返回安全结果，而不是接受错误数据。
    fun decodeRejectsCorruptedFrames() {
        val valid = response(0x04, hex("0C E4 0C EE"))
        val badChecksum = valid.copyOf().apply { this[lastIndex - 1] = (this[lastIndex - 1] + 1).toByte() }
        val badTerminator = valid.copyOf().apply { this[lastIndex] = 0x00 }
        val truncated = valid.copyOf(valid.size - 1)
        assertFalse(JbdProtocol.decode(badChecksum).isSuccess)
        assertFalse(JbdProtocol.decode(badTerminator).isSuccess)
        assertFalse(JbdProtocol.decode(truncated).isSuccess)
    }

    @Test
    //MARK:测试信息
    //验证解析基本信息and温度能够按预期解析字段、单位和边界值。
    fun parsesBasicInfoAndTemperatures() {
        val data = hex(
            "17 00 FF 9C 02 D0 03 E8 00 02 20 78 00 01 00 00 00 00 10 48 03 0F 02 0B 76 0B 82"
        )
        val raw = response(0x03, data)
        val frame = JbdProtocol.decode(raw).getOrThrow()
        val message = JbdProtocol.parse(frame).getOrThrow() as JbdMessage.BasicInfo
        assertEquals(58.88, message.value.totalVoltageV, 0.001)
        assertEquals(-1.0, message.value.currentA, 0.001)
        assertEquals(7.2, message.value.remainingCapacityAh, 0.001)
        assertEquals(10.0, message.value.nominalCapacityAh, 0.001)
        assertEquals(2, message.value.cycleCount)
        assertEquals(listOf(20.3, 21.5), message.value.temperaturesC)
    }

    @Test
    //MARK:测试单体压差
    //验证解析动态单体数量and压差能够按预期解析字段、单位和边界值。
    fun parsesDynamicCellCountAndDelta() {
        val raw = response(0x04, hex("0C E4 0C EE 0C DA 0C E9"))
        val frame = JbdProtocol.decode(raw).getOrThrow()
        val message = JbdProtocol.parse(frame).getOrThrow() as JbdMessage.Cells
        assertEquals(4, message.value.millivolts.size)
        assertEquals(3310, message.value.maximumMv)
        assertEquals(20, message.value.deltaMv)
    }

    @Test
    //MARK:测V12扩展
    //验证解析v12扩展字段能够按预期解析字段、单位和边界值。
    fun parsesV12ExtensionFields() {
        val standard = hex(
            "17 00 FF 9C 02 D0 03 E8 00 02 20 78 00 01 00 00 00 00 10 48 03 0F 02 0B 76 0B 82"
        )
        val extension = hex("37 04 01 03 84 02 D0 FF 9C")
        val message = JbdProtocol.parse(
            JbdProtocol.decode(response(0x03, byteArrayOf(*standard, *extension))).getOrThrow()
        ).getOrThrow() as JbdMessage.BasicInfo
        assertEquals(55, message.value.humidityPercent)
        assertEquals(0x0401, message.value.alarmMask)
        assertEquals(9.0, message.value.fullChargeCapacityAh ?: 0.0, 0.001)
        assertEquals(-100, message.value.balancingCurrentMa)
        assertEquals(90.0, message.value.estimatedSohPercent ?: 0.0, 0.001)
    }

    @Test
    //MARK:测试容量
    //验证解析大容量单位能够按预期解析字段、单位和边界值。
    fun parsesHighCapacityUnits() {
        val data = hex(
            "17 00 FF 9C 02 D0 03 E8 00 02 20 78 00 01 00 00 00 00 10 48 83 0F 02 0B 76 0B 82"
        )
        val message = JbdProtocol.parse(JbdProtocol.decode(response(0x03, data)).getOrThrow())
            .getOrThrow() as JbdMessage.BasicInfo
        assertEquals(-10.0, message.value.currentA, 0.001)
        assertEquals(72.0, message.value.remainingCapacityAh, 0.001)
        assertEquals(100.0, message.value.nominalCapacityAh, 0.001)
    }

    //MARK:构造响应报文
    //根据命令与负载计算 DD/77 二补数校验，构造协议测试使用的合法响应帧。
    private fun response(command: Int, data: ByteArray): ByteArray {
        val sum = 0 + data.size + data.sumOf { it.toInt() and 0xFF }
        val checksum = (-sum) and 0xFFFF
        return byteArrayOf(
            0xDD.toByte(), command.toByte(), 0, data.size.toByte(), *data,
            (checksum shr 8).toByte(), checksum.toByte(), 0x77
        )
    }

    //MARK:解析十六进制
    //把空格分隔的十六进制文本转换为字节数组，使协议示例保持可读。
    private fun hex(value: String): ByteArray = value.split(" ")
        .filter { it.isNotBlank() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
