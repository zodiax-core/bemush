package com.campusmesh.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class AdvertisePayloadTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val encoded = AdvertisePayload.encode(id)

        assertEquals(AdvertisePayload.SIZE_BYTES, encoded.size)
        assertEquals(id, AdvertisePayload.decode(encoded))
    }

    @Test
    fun rejectsWrongMagic() {
        val encoded = AdvertisePayload.encode(UUID.randomUUID())
        encoded[0] = 0x00
        assertNull(AdvertisePayload.decode(encoded))
    }

    @Test
    fun rejectsWrongVersion() {
        val encoded = AdvertisePayload.encode(UUID.randomUUID())
        encoded[2] = 2
        assertNull(AdvertisePayload.decode(encoded))
    }

    @Test
    fun rejectsShortPayload() {
        assertNull(AdvertisePayload.decode(byteArrayOf(0x43, 0x4D, 1)))
        assertNull(AdvertisePayload.decode(null))
    }

    @Test
    fun differentNodesProduceDifferentPayloads() {
        val a = AdvertisePayload.encode(UUID.randomUUID())
        val b = AdvertisePayload.encode(UUID.randomUUID())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun shortLabelIsEightHexCharacters() {
        val id = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789")
        assertEquals("ABCDEF01", AdvertisePayload.shortLabel(id))
    }
}
