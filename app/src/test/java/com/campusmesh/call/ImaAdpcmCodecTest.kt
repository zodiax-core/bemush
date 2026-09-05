package com.campusmesh.call

import com.campusmesh.call.audio.ImaAdpcmCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ImaAdpcmCodecTest {

    @Test
    fun testImaAdpcmFrameSizeAndSelfContainment() {
        val samples = ShortArray(ImaAdpcmCodec.SAMPLES_PER_FRAME) { i ->
            // Generate a synthetic 440 Hz tone at 8000 Hz sample rate
            (sin(2.0 * Math.PI * 440.0 * i / 8000.0) * 12000.0).toInt().toShort()
        }

        val encodedBuffer = ByteArray(ImaAdpcmCodec.ENCODED_FRAME_SIZE)
        val state = ImaAdpcmCodec.State()

        val encodedSize = ImaAdpcmCodec.encode(
            pcmSamples = samples,
            offset = 0,
            length = samples.size,
            state = state,
            sequenceNumber = 42,
            outBuffer = encodedBuffer,
        )

        assertEquals("Encoded frame must be exactly 160 bytes", ImaAdpcmCodec.ENCODED_FRAME_SIZE, encodedSize)
        assertEquals("Sequence number in header must match", 42.toByte(), encodedBuffer[3])

        // Decode the frame
        val decodedSamples = ShortArray(ImaAdpcmCodec.SAMPLES_PER_FRAME)
        val decodedCount = ImaAdpcmCodec.decode(
            encodedFrame = encodedBuffer,
            frameOffset = 0,
            frameLength = encodedSize,
            outSamples = decodedSamples,
            outOffset = 0,
        )

        assertEquals("Decoded sample count must match input count", samples.size, decodedCount)

        // Verify signal-to-noise ratio is high (PCM reconstruction follows original waveform)
        var totalError = 0.0
        var totalSignal = 0.0
        for (i in samples.indices) {
            val original = samples[i].toDouble()
            val reconstructed = decodedSamples[i].toDouble()
            val diff = original - reconstructed
            totalError += diff * diff
            totalSignal += original * original
        }

        val snr = 10.0 * Math.log10(totalSignal / (totalError + 1.0))
        assertTrue("SNR must be greater than 15 dB for speech quality (actual: $snr dB)", snr > 15.0)
    }

    @Test
    fun testCallSignalingSerialization() {
        val offer = CallPacket.Offer(
            callId = "call-123-abc",
            callerName = "Alice",
            callerAvatarPath = "/path/to/avatar.jpg",
            timestamp = 1700000000L,
        )

        val offerJson = CallPacket.serialize(offer)
        assertTrue(offerJson.contains("\"type\":\"CALL_OFFER\""))
        assertTrue(offerJson.contains("\"callId\":\"call-123-abc\""))

        val deserializedOffer = CallPacket.deserialize(offerJson)
        assertTrue(deserializedOffer is CallPacket.Offer)
        val castOffer = deserializedOffer as CallPacket.Offer
        assertEquals(offer.callId, castOffer.callId)
        assertEquals(offer.callerName, castOffer.callerName)
        assertEquals(offer.callerAvatarPath, castOffer.callerAvatarPath)

        // Test AudioFrame serialization
        val audioBytes = byteArrayOf(1, 2, 3, 4, 5, -1, -2, -3)
        val audioPacket = CallPacket.AudioFrame(
            callId = "call-123-abc",
            seq = 7,
            audioData = audioBytes,
        )
        val audioJson = CallPacket.serialize(audioPacket)
        assertTrue(audioJson.contains("\"type\":\"CALL_AUDIO\""))

        val deserializedAudio = CallPacket.deserialize(audioJson)
        assertTrue(deserializedAudio is CallPacket.AudioFrame)
        val castAudio = deserializedAudio as CallPacket.AudioFrame
        assertEquals(7, castAudio.seq)
        assertArrayEquals(audioBytes, castAudio.audioData)
    }
}
