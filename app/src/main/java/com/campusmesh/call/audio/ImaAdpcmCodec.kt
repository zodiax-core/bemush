package com.campusmesh.call.audio

/**
 * Standard IMA-ADPCM (Interactive Multimedia Association ADPCM) 4-bit speech codec.
 * Compresses 16-bit linear PCM down to 4-bit samples (4:1 compression ratio).
 *
 * Each audio packet is formatted as:
 *  - 2 bytes: Initial predicted PCM sample (Little-Endian Short)
 *  - 1 byte:  Initial step index (0..88)
 *  - 1 byte:  Sequence number (0..255)
 *  - 156 bytes: 312 compressed 4-bit nibbles (representing 39ms of 8000 Hz audio)
 * Total: exactly 160 bytes per packet.
 *
 * Being self-contained ensures that any dropped packet over BLE mesh causes
 * zero drift or desynchronization — the receiver immediately resyncs on the next packet.
 */
object ImaAdpcmCodec {

    const val SAMPLES_PER_FRAME = 312
    const val ENCODED_FRAME_SIZE = 160 // 4 bytes header + 156 bytes payload

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8,
    )

    private val STEP_SIZE_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    class State(
        var predictedSample: Int = 0,
        var stepIndex: Int = 0,
    )

    /**
     * Encodes 312 PCM Short samples into a 160-byte frame with state header.
     */
    fun encode(
        pcmSamples: ShortArray,
        offset: Int = 0,
        length: Int = SAMPLES_PER_FRAME,
        state: State,
        sequenceNumber: Byte,
        outBuffer: ByteArray,
    ): Int {
        val count = minOf(length, pcmSamples.size - offset)

        // Write header: 2 bytes initial predictor, 1 byte initial stepIndex, 1 byte seq
        val initPred = state.predictedSample.coerceIn(-32768, 32767)
        outBuffer[0] = (initPred and 0xFF).toByte()
        outBuffer[1] = ((initPred shr 8) and 0xFF).toByte()
        outBuffer[2] = state.stepIndex.coerceIn(0, 88).toByte()
        outBuffer[3] = sequenceNumber

        var outIdx = 4
        var i = 0
        while (i < count) {
            val s1 = pcmSamples[offset + i]
            val nibble1 = encodeSample(s1.toInt(), state)

            val nibble2 = if (i + 1 < count) {
                val s2 = pcmSamples[offset + i + 1]
                encodeSample(s2.toInt(), state)
            } else 0

            outBuffer[outIdx++] = ((nibble2 shl 4) or (nibble1 and 0x0F)).toByte()
            i += 2
        }

        return outIdx
    }

    private fun encodeSample(sample: Int, state: State): Int {
        val step = STEP_SIZE_TABLE[state.stepIndex]
        var diff = sample - state.predictedSample
        var sign = 0
        if (diff < 0) {
            sign = 8
            diff = -diff
        }

        var delta = 0
        var vpdiff = step shr 3

        if (diff >= step) {
            delta = delta or 4
            diff -= step
            vpdiff += step
        }
        val step2 = step shr 1
        if (diff >= step2) {
            delta = delta or 2
            diff -= step2
            vpdiff += step2
        }
        val step4 = step shr 2
        if (diff >= step4) {
            delta = delta or 1
            vpdiff += step4
        }

        if (sign != 0) {
            state.predictedSample -= vpdiff
        } else {
            state.predictedSample += vpdiff
        }
        state.predictedSample = state.predictedSample.coerceIn(-32768, 32767)

        val code = delta or sign

        state.stepIndex += INDEX_TABLE[code]
        state.stepIndex = state.stepIndex.coerceIn(0, 88)

        return code
    }

    /**
     * Decodes a 160-byte frame into up to 312 PCM Short samples.
     * Returns the number of samples decoded into [outSamples].
     */
    fun decode(
        encodedFrame: ByteArray,
        frameOffset: Int = 0,
        frameLength: Int = encodedFrame.size,
        outSamples: ShortArray,
        outOffset: Int = 0,
    ): Int {
        if (frameLength < 4) return 0

        // Parse header
        val pLow = encodedFrame[frameOffset].toInt() and 0xFF
        val pHigh = encodedFrame[frameOffset + 1].toInt()
        val initPred = ((pHigh shl 8) or pLow).toShort().toInt()
        val initIndex = encodedFrame[frameOffset + 2].toInt() and 0xFF

        val state = State(
            predictedSample = initPred,
            stepIndex = initIndex.coerceIn(0, 88),
        )

        var sampleIdx = outOffset
        var byteIdx = frameOffset + 4
        val endByteIdx = frameOffset + frameLength

        while (byteIdx < endByteIdx && sampleIdx < outSamples.size) {
            val byteVal = encodedFrame[byteIdx++].toInt() and 0xFF

            val nibble1 = byteVal and 0x0F
            outSamples[sampleIdx++] = decodeSample(nibble1, state).toShort()

            if (sampleIdx < outSamples.size) {
                val nibble2 = (byteVal shr 4) and 0x0F
                outSamples[sampleIdx++] = decodeSample(nibble2, state).toShort()
            }
        }

        return sampleIdx - outOffset
    }

    private fun decodeSample(nibble: Int, state: State): Int {
        val step = STEP_SIZE_TABLE[state.stepIndex]

        var vpdiff = step shr 3
        if ((nibble and 4) != 0) vpdiff += step
        if ((nibble and 2) != 0) vpdiff += (step shr 1)
        if ((nibble and 1) != 0) vpdiff += (step shr 2)

        if ((nibble and 8) != 0) {
            state.predictedSample -= vpdiff
        } else {
            state.predictedSample += vpdiff
        }
        state.predictedSample = state.predictedSample.coerceIn(-32768, 32767)

        state.stepIndex += INDEX_TABLE[nibble]
        state.stepIndex = state.stepIndex.coerceIn(0, 88)

        return state.predictedSample
    }
}
