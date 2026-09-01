package com.campusmesh.crypto

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeKeyManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val privateKey: PrivateKey
    val publicKey: PublicKey
    val publicKeyBase64: String

    init {
        val existingPublic = prefs.getString(KEY_PUBLIC, null)
        val existingPrivate = prefs.getString(KEY_PRIVATE, null)

        val pair = if (existingPublic != null && existingPrivate != null) {
            try {
                val pubBytes = Base64.decode(existingPublic, Base64.NO_WRAP)
                val privBytes = Base64.decode(existingPrivate, Base64.NO_WRAP)
                val keyFactory = KeyFactory.getInstance("RSA")
                val pub = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
                val priv = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                Pair(pub, priv)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load keys from storage, generating new key pair")
                generateAndSaveKeys()
            }
        } else {
            generateAndSaveKeys()
        }
        publicKey = pair.first
        privateKey = pair.second
        publicKeyBase64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP).trim()
    }

    private fun generateAndSaveKeys(): Pair<PublicKey, PrivateKey> {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val pair = keyGen.generateKeyPair()
        val pubBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP).trim()
        val privBase64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP).trim()

        prefs.edit()
            .putString(KEY_PUBLIC, pubBase64)
            .putString(KEY_PRIVATE, privBase64)
            .apply()

        return Pair(pair.public, pair.private)
    }

    fun encrypt(plaintext: String, recipientPublicKeyBase64: String): String? {
        return try {
            val pubBytes = Base64.decode(recipientPublicKeyBase64, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance("RSA")
            val recipientPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))

            // 1. Generate AES key
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, SecureRandom())
            val aesKey = keyGen.generateKey()

            // 2. Generate IV
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)

            // 3. Encrypt message with AES-GCM
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ciphertext = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // 4. Encrypt AES key with recipient RSA public key
            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey)
            val encryptedAesKeyBytes = rsaCipher.doFinal(aesKey.encoded)

            val payload = EncryptedPayload(
                encryptedAesKey = Base64.encodeToString(encryptedAesKeyBytes, Base64.NO_WRAP).trim(),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP).trim(),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP).trim(),
                senderPublicKey = publicKeyBase64,
            )

            json.encodeToString(payload)
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt message")
            null
        }
    }

    fun decrypt(payloadJson: String): DecryptedMessage? {
        return try {
            val payload = json.decodeFromString<EncryptedPayload>(payloadJson)
            val encryptedAesKeyBytes = Base64.decode(payload.encryptedAesKey, Base64.NO_WRAP)
            val iv = Base64.decode(payload.iv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(payload.ciphertext, Base64.NO_WRAP)

            // 1. Decrypt AES key with local RSA private key
            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
            val aesKeyBytes = rsaCipher.doFinal(encryptedAesKeyBytes)
            val aesKey: SecretKey = SecretKeySpec(aesKeyBytes, "AES")

            // 2. Decrypt message with AES-GCM
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val plaintextBytes = aesCipher.doFinal(ciphertext)

            DecryptedMessage(
                plaintext = String(plaintextBytes, Charsets.UTF_8),
                senderPublicKey = payload.senderPublicKey,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt message")
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "campusmesh_crypto"
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_PRIVATE = "private_key"
    }
}

data class DecryptedMessage(
    val plaintext: String,
    val senderPublicKey: String,
)
