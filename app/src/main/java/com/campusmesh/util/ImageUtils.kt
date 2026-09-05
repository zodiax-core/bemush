package com.campusmesh.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object ImageUtils {

    private const val PROFILES_DIR = "profiles"
    private const val NOMEDIA_FILE = ".nomedia"

    /**
     * Copies and compresses a user-selected profile image into app private internal storage.
     * Ensures gallery apps do not index the folder by including `.nomedia`.
     * Deletes [previousFilePath] if provided.
     */
    fun saveProfileImage(context: Context, imageUri: Uri, previousFilePath: String? = null): File? {
        return try {
            if (!previousFilePath.isNullOrBlank()) {
                deleteImageFile(previousFilePath)
            }

            val dir = getProfilesDir(context)
            val fileName = "profile_${System.currentTimeMillis()}.jpg"
            val targetFile = File(dir, fileName)

            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) return null

            val scaledBitmap = scaleDownBitmap(originalBitmap, maxDimension = 512)
            FileOutputStream(targetFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            Timber.i("Saved profile image to private storage: %s", targetFile.absolutePath)
            targetFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to save profile image")
            null
        }
    }

    /**
     * Saves Base64 image data received from BLE into a local private profile image file.
     */
    fun saveBase64Avatar(context: Context, base64Data: String, peerNodeId: String, previousFilePath: String? = null): File? {
        return try {
            if (!previousFilePath.isNullOrBlank()) {
                deleteImageFile(previousFilePath)
            }

            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val dir = getProfilesDir(context)
            val fileName = "peer_${peerNodeId.take(8)}_${System.currentTimeMillis()}.jpg"
            val targetFile = File(dir, fileName)

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }

            Timber.i("Saved peer avatar from BLE: %s", targetFile.absolutePath)
            targetFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to save Base64 avatar")
            null
        }
    }

    /**
     * Encodes a profile image for BLE transmission.
     * Uses 144×144 px at 80% JPEG quality to deliver sharp, crisp profile pictures
     * while remaining compact enough (~3–5 KB) for fast chunked BLE transmission.
     */
    fun encodeFileToBase64(file: File): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val scaled = scaleDownBitmap(bitmap, maxDimension = 144)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "Failed to encode file to base64")
            null
        }
    }

    /**
     * Computes SHA-256 hash of image file for profile change detection.
     */
    fun computeFileHash(file: File): String? {
        return try {
            val bytes = file.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute file hash")
            null
        }
    }

    fun deleteImageFile(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        try {
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                Timber.i("Deleted old profile image: %s (success=%b)", filePath, deleted)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error deleting image file: %s", filePath)
        }
    }

    private fun getProfilesDir(context: Context): File {
        val dir = File(context.filesDir, PROFILES_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomedia = File(dir, NOMEDIA_FILE)
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    private fun scaleDownBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (width > height) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / ratio).toInt()
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * ratio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
