package com.campusmesh.call

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Base64

@Serializable
sealed class CallPacket {
    abstract val callId: String

    @Serializable
    @SerialName("CALL_OFFER")
    data class Offer(
        override val callId: String,
        val callerName: String,
        val callerAvatarPath: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    ) : CallPacket()

    @Serializable
    @SerialName("CALL_RINGING")
    data class Ringing(
        override val callId: String,
    ) : CallPacket()

    @Serializable
    @SerialName("CALL_ANSWER")
    data class Answer(
        override val callId: String,
    ) : CallPacket()

    @Serializable
    @SerialName("CALL_DECLINE")
    data class Decline(
        override val callId: String,
        val reason: String = "declined",
    ) : CallPacket()

    @Serializable
    @SerialName("CALL_HANGUP")
    data class Hangup(
        override val callId: String,
        val durationSeconds: Long = 0,
    ) : CallPacket()

    @Serializable
    @SerialName("CALL_AUDIO")
    data class AudioFrame(
        override val callId: String,
        val seq: Int,
        val audioDataB64: String,
    ) : CallPacket() {
        val audioData: ByteArray get() = try {
            Base64.getDecoder().decode(audioDataB64)
        } catch (_: Exception) {
            ByteArray(0)
        }

        constructor(callId: String, seq: Int, audioData: ByteArray) : this(
            callId = callId,
            seq = seq,
            audioDataB64 = Base64.getEncoder().encodeToString(audioData),
        )
    }

    companion object {
        const val TYPE_CALL_OFFER = "CALL_OFFER"
        const val TYPE_CALL_RINGING = "CALL_RINGING"
        const val TYPE_CALL_ANSWER = "CALL_ANSWER"
        const val TYPE_CALL_DECLINE = "CALL_DECLINE"
        const val TYPE_CALL_HANGUP = "CALL_HANGUP"
        const val TYPE_CALL_AUDIO = "CALL_AUDIO"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun serialize(packet: CallPacket): String {
            return json.encodeToString(packet)
        }

        fun deserialize(jsonStr: String): CallPacket? {
            return try {
                if (!jsonStr.startsWith("{") || !jsonStr.contains("CALL_")) return null
                json.decodeFromString<CallPacket>(jsonStr)
            } catch (e: Exception) {
                Timber.w(e, "Failed to deserialize CallPacket JSON")
                null
            }
        }
    }
}
