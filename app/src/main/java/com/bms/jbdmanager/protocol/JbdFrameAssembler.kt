package com.bms.jbdmanager.protocol

//MARK:报文组装器
//JbdFrameAssembler 缓存碎片数据并识别完整协议边界，用于处理报文。
class JbdFrameAssembler {
    private val buffer = ArrayList<Byte>()

    //MARK:组装报文
    //append 把 BLE 通知片段追加到缓存，自动丢弃噪声并返回其中所有完整报文。
    fun append(chunk: ByteArray): List<ByteArray> {
        // BLE 通知没有报文边界保证：一次通知可能是半帧，也可能连续包含多帧，因此先统一追加到缓存。
        buffer.addAll(chunk.toList())
        val frames = mutableListOf<ByteArray>()

        while (true) {
            while (buffer.isNotEmpty() && (buffer.first().toInt() and 0xFF) != 0xDD) {
                // 丢弃帧头前的噪声字节，使后续合法帧能够重新同步，而不是清空整个缓存。
                buffer.removeAt(0)
            }
            if (buffer.size < 4) break

            val dataLength = buffer[3].toInt() and 0xFF
            val frameLength = dataLength + 7
            if (buffer.size < frameLength) break

            if ((buffer[frameLength - 1].toInt() and 0xFF) != 0x77) {
                // 声明长度对应位置不是帧尾时只移动一个字节，继续寻找下一个可能的 DD 帧头。
                buffer.removeAt(0)
                continue
            }

            frames += ByteArray(frameLength) { buffer[it] }
            repeat(frameLength) { buffer.removeAt(0) }
        }
        return frames
    }

    //MARK:清空数据
    //clear 清除该模块保存的状态，使下一次读取回到未记录时的默认值。
    fun clear() = buffer.clear()
}
