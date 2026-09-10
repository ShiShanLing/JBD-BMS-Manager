package com.bms.jbdmanager.protocol

import com.bms.jbdmanager.protocol.JbdProtocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

//MARK:测试认证协议
//JbdAuthProtocolTest 验证 JbdAuthProtocol 的正常流程、边界输入和需要长期保持的回归行为。
class JbdAuthProtocolTest {
    @Test
    //MARK:测试认证请求
    //验证buildsappkeyandrandomrequest场景的关键输出，防止后续修改破坏既有行为。
    fun buildsAppKeyAndRandomRequest() {
        assertEquals("FF AA 15 06 30 30 30 30 30 30 3B", JbdAuthProtocol.appKey().toHex())
        assertEquals("FF AA 17 00 17", JbdAuthProtocol.randomRequest().toHex())
    }

    @Test
    //MARK:测试密码加密
    //验证encryptsuser密码withmacandrandombyte场景的关键输出，防止后续修改破坏既有行为。
    fun encryptsUserPasswordWithMacAndRandomByte() {
        val frame = JbdAuthProtocol.userPassword("123123", "01:02:03:04:05:06", 0x10).getOrThrow()
        assertEquals("FF AA 18 06 40 40 40 45 47 45 AF", frame.toHex())
    }

    @Test
    //MARK:测试认证
    //验证decodesandvalidatesauthresponse能够按预期解析字段、单位和边界值。
    fun decodesAndValidatesAuthResponse() {
        val valid = hex("FF AA 15 01 02 18")
        val decoded = JbdAuthProtocol.decode(valid).getOrThrow()
        assertEquals(JbdAuthProtocol.SEND_APP_KEY, decoded.command)
        assertEquals(listOf(0x02.toByte()), decoded.data.toList())
        assertTrue(JbdAuthProtocol.decode(valid.copyOf().apply { this[lastIndex] = 0x00 }).isFailure)
    }

    //MARK:解析十六进制
    //把空格分隔的十六进制文本转换为字节数组，使协议示例保持可读。
    private fun hex(value: String): ByteArray = value.split(" ")
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
