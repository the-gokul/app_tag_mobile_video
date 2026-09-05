package com.nordic.tagmobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TagCommand {
    const val START: Byte = 0x01
    const val STOP: Byte = 0x02
    const val TIME: Byte = 0x03

    fun timePayload(unixMs: Long): ByteArray {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(unixMs))
        val strBytes = dateStr.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(1 + strBytes.size)
            .put(TIME)
            .put(strBytes)
            .array()
    }

    fun startPayload(): ByteArray = byteArrayOf(START)

    fun stopPayload(): ByteArray = byteArrayOf(STOP)
}
