package com.campusmesh.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Compact identity payload carried in BLE manufacturer data.
 *
 * Layout (19 bytes):
 * - magic: 0x43 0x4D ("CM")
 * - version: 1
 * - nodeId: 16-byte UUID
 */
object AdvertisePayload {
    const val VERSION: Byte = 1
    const val SIZE_BYTES: Int = 19

    private const val MAGIC_0: Byte = 0x43
    private const val MAGIC_1: Byte = 0x4D

    val filterPrefix: ByteArray = byteArrayOf(MAGIC_0, MAGIC_1, VERSION)
    val filterMask: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    fun encode(nodeId: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(VERSION)
        buffer.putLong(nodeId.mostSignificantBits)
        buffer.putLong(nodeId.leastSignificantBits)
        return buffer.array()
    }

    fun decode(manufacturerData: ByteArray?): UUID? {
        if (manufacturerData == null || manufacturerData.size < SIZE_BYTES) return null
        if (manufacturerData[0] != MAGIC_0 || manufacturerData[1] != MAGIC_1) return null
        if (manufacturerData[2] != VERSION) return null
        val buffer = ByteBuffer.wrap(manufacturerData, 3, 16).order(ByteOrder.BIG_ENDIAN)
        return UUID(buffer.long, buffer.long)
    }

    fun shortLabel(nodeId: UUID): String {
        val hex = nodeId.toString().replace("-", "")
        return hex.substring(0, 8).uppercase()
    }
}
