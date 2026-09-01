package com.campusmesh.profile

import android.content.Context
import android.net.Uri
import com.campusmesh.identity.LocalNodeIdStore
import com.campusmesh.util.ImageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localNodeIdStore: LocalNodeIdStore,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _localProfile = MutableStateFlow(
        LocalProfileState(
            nodeId = localNodeIdStore.nodeId.toString(),
            displayName = prefs.getString(KEY_DISPLAY_NAME, "Campus Student") ?: "Campus Student",
            avatarPath = prefs.getString(KEY_AVATAR_PATH, null),
            version = prefs.getLong(KEY_VERSION, 1L),
            contentHash = prefs.getString(KEY_CONTENT_HASH, null),
        )
    )
    val localProfile: StateFlow<LocalProfileState> = _localProfile.asStateFlow()

    fun updateProfileImage(imageUri: Uri) {
        val currentPath = _localProfile.value.avatarPath
        val savedFile = ImageUtils.saveProfileImage(context, imageUri, previousFilePath = currentPath)
        if (savedFile != null) {
            updateProfile(
                displayName = _localProfile.value.displayName,
                avatarPath = savedFile.absolutePath,
            )
        }
    }

    fun updateProfile(displayName: String, avatarPath: String? = _localProfile.value.avatarPath) {
        val trimmed = displayName.trim().ifBlank { "Campus Student" }
        val newVersion = _localProfile.value.version + 1

        val avatarFile = avatarPath?.let { File(it) }
        val imageHash = avatarFile?.takeIf { it.exists() }?.let { ImageUtils.computeFileHash(it) } ?: ""
        val hash = computeContentHash(trimmed, imageHash)

        prefs.edit()
            .putString(KEY_DISPLAY_NAME, trimmed)
            .putString(KEY_AVATAR_PATH, avatarPath)
            .putLong(KEY_VERSION, newVersion)
            .putString(KEY_CONTENT_HASH, hash)
            .apply()

        _localProfile.update {
            it.copy(
                displayName = trimmed,
                avatarPath = avatarPath,
                version = newVersion,
                contentHash = hash,
            )
        }
    }

    private fun computeContentHash(displayName: String, avatarHash: String): String {
        return try {
            val input = "$displayName:$avatarHash"
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            "hash_${System.currentTimeMillis()}"
        }
    }

    companion object {
        private const val PREFS_NAME = "campusmesh_profile"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AVATAR_PATH = "avatar_path"
        private const val KEY_VERSION = "profile_version"
        private const val KEY_CONTENT_HASH = "content_hash"
    }
}

data class LocalProfileState(
    val nodeId: String,
    val displayName: String,
    val avatarPath: String?,
    val version: Long,
    val contentHash: String?,
)
