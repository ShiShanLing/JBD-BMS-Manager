package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.protocol.JbdProtocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdProtocolTest {
    @Test
    fun readCommandsMatchPublishedProtocol() {
        assertEquals("DD A5 03 00 FF FD 77", JbdProtocol.readCommand(0x03).toHex())
        assertEquals("DD A5 04 00 FF FC 77", JbdProtocol.readCommand(0x04).toHex())
        assertEquals("DD A5 05 00 FF FB 77", JbdProtocol.readCommand(0x05).toHex())
    }

    @Test
    fun assemblerHandlesSplitNotifications() {
        val raw = hex("DD 05 00 03 4A 42 44 FF 2C 77")
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.append(raw.copyOfRange(0, 4)).isEmpty())
        val result = assembler.append(raw.copyOfRange(4, raw.size))
        assertEquals(1, result.size)
        assertEquals(raw.toList(), result.single().toList())
    }

    @Test
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
    fun parsesDynamicCellCountAndDelta() {
        val raw = response(0x04, hex("0C E4 0C EE 0C DA 0C E9"))
        val frame = JbdProtocol.decode(raw).getOrThrow()
        val message = JbdProtocol.parse(frame).getOrThrow() as JbdMessage.Cells
        assertEquals(4, message.value.millivolts.size)
        assertEquals(3310, message.value.maximumMv)
        assertEquals(20, message.value.deltaMv)
    }

    private fun response(command: Int, data: ByteArray): ByteArray {
        val sum = 0 + data.size + data.sumOf { it.toInt() and 0xFF }
        val checksum = (-sum) and 0xFFFF
        return byteArrayOf(
            0xDD.toByte(), command.toByte(), 0, data.size.toByte(), *data,
            (checksum shr 8).toByte(), checksum.toByte(), 0x77
        )
    }

    private fun hex(value: String): ByteArray = value.split(" ")
        .filter { it.isNotBlank() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
