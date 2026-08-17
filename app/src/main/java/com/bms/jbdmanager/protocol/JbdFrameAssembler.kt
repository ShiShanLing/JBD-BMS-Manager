package com.bms.jbdmanager.protocol

class JbdFrameAssembler {
    private val buffer = ArrayList<Byte>()

    fun append(chunk: ByteArray): List<ByteArray> {
        buffer.addAll(chunk.toList())
        val frames = mutableListOf<ByteArray>()

        while (true) {
            while (buffer.isNotEmpty() && (buffer.first().toInt() and 0xFF) != 0xDD) {
                buffer.removeAt(0)
            }
            if (buffer.size < 4) break

            val dataLength = buffer[3].toInt() and 0xFF
            val frameLength = dataLength + 7
            if (buffer.size < frameLength) break

            if ((buffer[frameLength - 1].toInt() and 0xFF) != 0x77) {
                buffer.removeAt(0)
                continue
            }

            frames += ByteArray(frameLength) { buffer[it] }
            repeat(frameLength) { buffer.removeAt(0) }
        }
        return frames
    }

    fun clear() = buffer.clear()
}
