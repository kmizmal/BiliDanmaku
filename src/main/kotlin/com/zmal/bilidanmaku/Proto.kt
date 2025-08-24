package com.zmal.bilidanmaku

import java.nio.ByteBuffer
import java.nio.ByteOrder

class Proto {
    var packetLen: Int = 0
    var headerLen: Int = 16
    var ver: Short = 0
    var op: Int = 0
    var seq: Int = 0
    var body: ByteArray = ByteArray(0)
    var maxBody: Int = 2048

    fun pack(): ByteArray {
        val bodyBytes = body
        packetLen = headerLen + bodyBytes.size

        val buffer = ByteBuffer.allocate(packetLen)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(packetLen)
        buffer.putShort(headerLen.toShort())
        buffer.putShort(ver)
        buffer.putInt(op)
        buffer.putInt(seq)
        buffer.put(bodyBytes)

        return buffer.array()
    }

    fun unpack(buf: ByteArray): Boolean {
        if (buf.size < headerLen) {
            println("包头不够")
            return false
        }

        val buffer = ByteBuffer.wrap(buf)
        buffer.order(ByteOrder.BIG_ENDIAN)

        packetLen = buffer.int
        val receivedHeaderLen = buffer.short.toInt()
        ver = buffer.short
        op = buffer.int
        seq = buffer.int

        // 添加headerLen检查
        if (receivedHeaderLen != headerLen) {
            println("包头长度不对")
            return false
        }

        if (packetLen !in 0..maxBody) {
            println("包体长不对 packetLen=$packetLen maxBody=$maxBody")
            return false
        }

        val bodyLen = packetLen - headerLen
        if (bodyLen > 0) {
            if (buf.size >= packetLen) {
                body = buf.copyOfRange(headerLen, packetLen)

//                if (ver == 0.toShort()) {
//                    if (op!=3)
//                    println("====> callback:op=$op ${getBodyAsString()}")
//                }
            } else {
                println("包体数据不完整")
                return false
            }
        }

        return true
    }

    fun setBody(text: String) {
        body = text.toByteArray(Charsets.UTF_8)
    }

    fun getBodyAsString(): String {
        return String(body, Charsets.UTF_8)
    }
}