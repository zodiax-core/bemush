package com.campusmesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {

    @Test
    fun testHybridEncryptionRoundtrip() {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val recipientPair = keyGen.generateKeyPair()
        val recipientPubKeyBase64 = Base64.getEncoder().encodeToString(recipientPair.public.encoded).trim()

        val plaintext = "Hello CampusMesh End-to-End Encryption!"

        val aesKeyGen = KeyGenerator.getInstance("AES")
        aesKeyGen.init(256, SecureRandom())
        val aesKey = aesKeyGen.generateKey()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ciphertext = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val pubBytes = Base64.getDecoder().decode(recipientPubKeyBase64)
        val pubKey = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encryptedAesKeyBytes = rsaCipher.doFinal(aesKey.encoded)

        val payload = EncryptedPayload(
            encryptedAesKey = Base64.getEncoder().encodeToString(encryptedAesKeyBytes).trim(),
            iv = Base64.getEncoder().encodeToString(iv).trim(),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext).trim(),
            senderPublicKey = "sender_pub_key_placeholder",
        )

        val decRsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        decRsaCipher.init(Cipher.DECRYPT_MODE, recipientPair.private)
        val decryptedAesKeyBytes = decRsaCipher.doFinal(Base64.getDecoder().decode(payload.encryptedAesKey))
        val decryptedAesKey: SecretKey = SecretKeySpec(decryptedAesKeyBytes, "AES")

        val decAesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decAesCipher.init(Cipher.DECRYPT_MODE, decryptedAesKey, GCMParameterSpec(128, Base64.getDecoder().decode(payload.iv)))
        val decryptedPlaintextBytes = decAesCipher.doFinal(Base64.getDecoder().decode(payload.ciphertext))

        val decryptedPlaintext = String(decryptedPlaintextBytes, Charsets.UTF_8)

        assertEquals(plaintext, decryptedPlaintext)
    }

    @Test
    fun testRelayCannotDecrypt() {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val recipientPair = keyGen.generateKeyPair()
        val relayPair = keyGen.generateKeyPair()

        val plaintext = "Secret message across relay nodes"
        val pubBytes = recipientPair.public.encoded
        val pubKey = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))

        val aesKeyGen = KeyGenerator.getInstance("AES")
        aesKeyGen.init(256, SecureRandom())
        val aesKey = aesKeyGen.generateKey()
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ciphertext = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encryptedAesKeyBytes = rsaCipher.doFinal(aesKey.encoded)

        var decryptionFailed = false
        try {
            val decRsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            decRsaCipher.init(Cipher.DECRYPT_MODE, relayPair.private)
            decRsaCipher.doFinal(encryptedAesKeyBytes)
        } catch (e: Exception) {
            decryptionFailed = true
        }

        org.junit.Assert.assertTrue(decryptionFailed)
    }
}
