package com.bms.jbdmanager.protocol

data class JbdAuthFrame(val command: Int, val data: ByteArray)

/** Read-session authentication used by newer JBD BLE modules (FF AA frames). */
object JbdAuthProtocol {
    const val SEND_APP_KEY = 0x15
    const val GET_RANDOM = 0x17
    const val SEND_PASSWORD = 0x18
    const val SEND_ROOT_PASSWORD = 0x1D

    private val rootPassword = byteArrayOf(
        0x4A, 0x42, 0x44, 0x62, 0x74, 0x70, 0x77, 0x64,
        0x21, 0x40, 0x23, 0x32, 0x30, 0x32, 0x33
    )

    fun appKey(): ByteArray = frame(SEND_APP_KEY, "000000".toByteArray(Charsets.US_ASCII))

    fun randomRequest(): ByteArray = frame(GET_RANDOM)

    fun userPassword(password: String, address: String, random: Int): Result<ByteArray> = runCatching {
        require(password.length == 6 && password.all(Char::isDigit)) { "蓝牙密码必须是6位数字" }
        val mac = parseAddress(address)
        val encrypted = ByteArray(6) { index ->
            (((mac[index].toInt() and 0xFF) xor password[index].code) + random).toByte()
        }
        frame(SEND_PASSWORD, encrypted)
    }

    fun rootPassword(address: String, random: Int): Result<ByteArray> = runCatching {
        val mac = parseAddress(address)
        val encrypted = ByteArray(rootPassword.size) { index ->
            val macByte = if (index < mac.size) mac[index].toInt() and 0xFF else 0
            ((macByte xor (rootPassword[index].toInt() and 0xFF)) + random).toByte()
        }
        frame(SEND_ROOT_PASSWORD, encrypted)
    }

    fun decode(raw: ByteArray): Result<JbdAuthFrame> = runCatching {
        require(raw.size >= 5) { "认证报文长度不足" }
        require(raw[0].u8() == 0xFF && raw[1].u8() == 0xAA) { "认证帧头错误" }
        val length = raw[3].u8()
        require(raw.size == length + 5) { "认证报文长度不匹配" }
        val expected = raw.last().u8()
        val actual = raw.copyOfRange(2, 4 + length).sumOf { it.u8() } and 0xFF
        require(expected == actual) { "认证报文校验失败" }
        JbdAuthFrame(raw[2].u8(), raw.copyOfRange(4, 4 + length))
    }

    private fun frame(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= 255)
        val body = byteArrayOf(command.toByte(), data.size.toByte(), *data)
        val checksum = body.sumOf { it.u8() } and 0xFF
        return byteArrayOf(0xFF.toByte(), 0xAA.toByte(), *body, checksum.toByte())
    }

    private fun parseAddress(address: String): ByteArray {
        val parts = address.split(':')
        require(parts.size == 6) { "蓝牙地址格式错误" }
        return parts.map { part ->
            require(part.length == 2)
            part.toInt(16).toByte()
        }.toByteArray()
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}
