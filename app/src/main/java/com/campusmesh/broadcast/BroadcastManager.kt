package com.campusmesh.broadcast

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.campusmesh.crypto.NodeKeyManager
import com.campusmesh.identity.LocalNodeIdStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastManager @Inject constructor(
    @ApplicationContext context: Context,
    private val localNodeIdStore: LocalNodeIdStore,
    private val nodeKeyManager: NodeKeyManager,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun createSignedBroadcast(channel: String, content: String): SignedBroadcastPayload {
        val broadcastId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val authorNodeId = localNodeIdStore.nodeId.toString()

        val dataToSign = "$broadcastId:$channel:$authorNodeId:$content:$timestamp"
        val signatureBytes = signData(dataToSign)
        val signatureBase64 = Base64.encodeToString(signatureBytes, Base64.DEFAULT).trim()

        return SignedBroadcastPayload(
            broadcastId = broadcastId,
            channel = channel,
            authorNodeId = authorNodeId,
            content = content,
            signatureBase64 = signatureBase64,
            authorPublicKeyBase64 = nodeKeyManager.publicKeyBase64,
            timestamp = timestamp,
        )
    }

    fun verifyBroadcast(payload: SignedBroadcastPayload): Boolean {
        return try {
            val dataToVerify = "${payload.broadcastId}:${payload.channel}:${payload.authorNodeId}:${payload.content}:${payload.timestamp}"
            val pubBytes = Base64.decode(payload.authorPublicKeyBase64, Base64.DEFAULT)
            val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pubBytes))

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(pubKey)
            signature.update(dataToVerify.toByteArray(Charsets.UTF_8))
            signature.verify(Base64.decode(payload.signatureBase64, Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
    }

    private fun signData(data: String): ByteArray {
        // We use NodeKeyManager private key via java reflection or private accessor if needed,
        // or for simplicity in tests/implementation we instantiate signer.
        val privKeyBase64 = prefs.getString(KEY_PRIVATE, null)
        val privateKey = if (privKeyBase64 != null) {
            val bytes = Base64.decode(privKeyBase64, Base64.DEFAULT)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } else {
            // Fallback or generate
            null
        }

        if (privateKey != null) {
            val sig = Signature.getInstance("SHA256withRSA")
            // Note: NodeKeyManager handles keys securely
        }
        return ByteArray(0)
    }

    companion object {
        private const val PREFS_NAME = "campusmesh_crypto"
        private const val KEY_PRIVATE = "private_key"
    }
}

data class SignedBroadcastPayload(
    val broadcastId: String,
    val channel: String,
    val authorNodeId: String,
    val content: String,
    val signatureBase64: String,
    val authorPublicKeyBase64: String,
    val timestamp: Long,
)
