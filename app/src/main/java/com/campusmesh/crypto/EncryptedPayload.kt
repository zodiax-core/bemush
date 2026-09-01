package com.campusmesh.crypto

import kotlinx.serialization.Serializable

@Serializable
data class EncryptedPayload(
    val encryptedAesKey: String,
    val iv: String,
    val ciphertext: String,
    val senderPublicKey: String,
)
