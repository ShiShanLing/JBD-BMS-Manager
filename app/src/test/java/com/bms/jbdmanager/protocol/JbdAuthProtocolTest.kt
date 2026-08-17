package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.protocol.JbdProtocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdAuthProtocolTest {
    @Test
    fun buildsAppKeyAndRandomRequest() {
        assertEquals("FF AA 15 06 30 30 30 30 30 30 3B", JbdAuthProtocol.appKey().toHex())
        assertEquals("FF AA 17 00 17", JbdAuthProtocol.randomRequest().toHex())
    }

    @Test
    fun encryptsUserPasswordWithMacAndRandomByte() {
        val frame = JbdAuthProtocol.userPassword("123123", "01:02:03:04:05:06", 0x10).getOrThrow()
        assertEquals("FF AA 18 06 40 40 40 45 47 45 AF", frame.toHex())
    }

    @Test
    fun decodesAndValidatesAuthResponse() {
        val valid = hex("FF AA 15 01 02 18")
        val decoded = JbdAuthProtocol.decode(valid).getOrThrow()
        assertEquals(JbdAuthProtocol.SEND_APP_KEY, decoded.command)
        assertEquals(listOf(0x02.toByte()), decoded.data.toList())
        assertTrue(JbdAuthProtocol.decode(valid.copyOf().apply { this[lastIndex] = 0x00 }).isFailure)
    }

    private fun hex(value: String): ByteArray = value.split(" ")
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
