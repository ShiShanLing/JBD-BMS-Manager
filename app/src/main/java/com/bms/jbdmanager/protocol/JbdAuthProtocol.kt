package com.bms.jbdmanager.protocol

//MARK:认证报文
//JbdAuthFrame 将认证报文相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
data class JbdAuthFrame(val command: Int, val data: ByteArray)

//MARK:认证协议
//JbdAuthProtocol 提供进程内共享的认证协议能力，并集中维护其状态、常量或纯计算入口。
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

    //MARK:应用密钥
    //appKey 按目标协议构造应用，包含规定的帧头、长度、负载和校验字段。
    fun appKey(): ByteArray = frame(SEND_APP_KEY, "000000".toByteArray(Charsets.US_ASCII))

    //MARK:请求随机码
    //randomRequest 按目标协议构造randomRequest，包含规定的帧头、长度、负载和校验字段。
    fun randomRequest(): ByteArray = frame(GET_RANDOM)

    //MARK:用户认证
    //userPassword 按目标协议构造userPassword，包含规定的帧头、长度、负载和校验字段。
    fun userPassword(password: String, address: String, random: Int): Result<ByteArray> = runCatching {
        require(password.length == 6 && password.all(Char::isDigit)) { "蓝牙密码必须是6位数字" }
        val mac = parseAddress(address)
        // 新版模块不是明文发送密码：每位数字先与对应 MAC 字节异或，再叠加设备返回的随机码。
        val encrypted = ByteArray(6) { index ->
            (((mac[index].toInt() and 0xFF) xor password[index].code) + random).toByte()
        }
        frame(SEND_PASSWORD, encrypted)
    }

    //MARK:读取授权
    //rootPassword 按目标协议构造rootPassword，包含规定的帧头、长度、负载和校验字段。
    fun rootPassword(address: String, random: Int): Result<ByteArray> = runCatching {
        val mac = parseAddress(address)
        val encrypted = ByteArray(rootPassword.size) { index ->
            val macByte = if (index < mac.size) mac[index].toInt() and 0xFF else 0
            ((macByte xor (rootPassword[index].toInt() and 0xFF)) + random).toByte()
        }
        frame(SEND_ROOT_PASSWORD, encrypted)
    }

    //MARK:解码报文
    //decode 校验报文头尾、长度和校验和，通过后拆出命令、状态及负载字段。
    fun decode(raw: ByteArray): Result<JbdAuthFrame> = runCatching {
        require(raw.size >= 5) { "认证报文长度不足" }
        require(raw[0].u8() == 0xFF && raw[1].u8() == 0xAA) { "认证帧头错误" }
        val length = raw[3].u8()
        require(raw.size == length + 5) { "认证报文长度不匹配" }
        val expected = raw.last().u8()
        val actual = raw.copyOfRange(2, 4 + length).sumOf { it.u8() } and 0xFF
        // FF/AA 协议使用低 8 位累加和，与经典 DD/77 的 16 位二补数校验不同。
        require(expected == actual) { "认证报文校验失败" }
        JbdAuthFrame(raw[2].u8(), raw.copyOfRange(4, 4 + length))
    }

    //MARK:构造认证帧
    //frame 按目标协议构造报文，包含规定的帧头、长度、负载和校验字段。
    private fun frame(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= 255)
        val body = byteArrayOf(command.toByte(), data.size.toByte(), *data)
        val checksum = body.sumOf { it.u8() } and 0xFF
        return byteArrayOf(0xFF.toByte(), 0xAA.toByte(), *body, checksum.toByte())
    }

    //MARK:解析地址
    //parseAddress 按 JBD 字段定义解析parseAddress，在读取前检查长度并换算协议单位。
    private fun parseAddress(address: String): ByteArray {
        val parts = address.split(':')
        require(parts.size == 6) { "蓝牙地址格式错误" }
        return parts.map { part ->
            require(part.length == 2)
            part.toInt(16).toByte()
        }.toByteArray()
    }

    //MARK:读取U8
    //把有符号 Byte 转换为 0 到 255，用于认证报文的长度和校验计算。
    private fun Byte.u8(): Int = toInt() and 0xFF
}
