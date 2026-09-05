package com.campusmesh.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.campusmesh.MainActivity
import com.campusmesh.R
import com.campusmesh.ui.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "CampusMesh Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for incoming CampusMesh direct & relay messages."
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun showIncomingMessageNotification(
        senderNodeId: String,
        senderName: String,
        messageText: String,
        avatarPath: String? = null,
    ) {
        try {
            val chatRoute = Routes.chat(senderNodeId, senderName)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_route", chatRoute)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                senderNodeId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Build composite icon: sender avatar (rounded) + small app logo badge in bottom-left
            val compositeIcon: Bitmap? = buildCompositeIcon(avatarPath)

            // MessagingStyle places the sender's avatar on the LEFT side of the notification,
            // replacing the app icon in the notification body and keeping the right side clear.
            val userPerson = Person.Builder()
                .setName("You")
                .build()

            val senderIconCompat = compositeIcon?.let { IconCompat.createWithBitmap(it) }

            val senderPerson = Person.Builder()
                .setName(senderName)
                .setKey(senderNodeId)
                .apply {
                    if (senderIconCompat != null) {
                        setIcon(senderIconCompat)
                    }
                }
                .build()

            val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
                .addMessage(messageText, System.currentTimeMillis(), senderPerson)
                .setGroupConversation(false)

            val builder = NotificationCompat.Builder(context, MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setStyle(messagingStyle)
                .setContentTitle(senderName)
                .setContentText(messageText)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            // On API < 28 (Android 8.0/8.1), MessagingStyle does not support native Person icons;
            // setLargeIcon provides backwards-compatibility for the left-side avatar on those versions.
            // On API 28+, omitting setLargeIcon prevents OEM skins from duplicating the image on the right side.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && compositeIcon != null) {
                builder.setLargeIcon(compositeIcon)
            }

            NotificationManagerCompat.from(context).notify(
                senderNodeId.hashCode(),
                builder.build(),
            )
            Timber.i("Posted notification for message from %s", senderName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to post message notification")
        }
    }

    /**
     * Creates a composite notification large icon:
     *  - Sender avatar fills the icon area, clipped to a circle
     *  - A small, rounded app logo is overlaid in the bottom-left corner (~28% size)
     * Falls back to just the app icon if no avatar is available.
     */
    private fun buildCompositeIcon(avatarPath: String?): Bitmap? {
        val iconSizePx = context.resources.getDimensionPixelSize(
            android.R.dimen.notification_large_icon_width
        ).takeIf { it > 0 } ?: 256

        // Load sender avatar
        val avatarBitmap: Bitmap? = avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
            } else null
        }

        // Load app icon from mipmap
        val appIconBitmap: Bitmap? = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_logo)
        } catch (_: Exception) { null }

        if (avatarBitmap == null && appIconBitmap == null) return null

        // Create output canvas
        val output = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        if (avatarBitmap != null) {
            // ── Draw circular sender avatar ────────────────────────────────────
            val scaledAvatar = Bitmap.createScaledBitmap(avatarBitmap, iconSizePx, iconSizePx, true)

            // Clip to circle
            val circleRadius = iconSizePx / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(circleRadius, circleRadius, circleRadius, paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaledAvatar, 0f, 0f, paint)
            paint.xfermode = null

            // ── Draw rounded app icon badge in bottom-left ─────────────────────
            if (appIconBitmap != null) {
                val badgeSize = (iconSizePx * 0.30f).toInt()
                val badgePadding = (iconSizePx * 0.04f)
                val badgeLeft = badgePadding
                val badgeTop = iconSizePx - badgeSize - badgePadding

                val scaledBadge = Bitmap.createScaledBitmap(appIconBitmap, badgeSize, badgeSize, true)

                // White circle background for contrast
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                }
                val bgRadius = badgeSize / 2f + (iconSizePx * 0.025f)
                canvas.drawCircle(
                    badgeLeft + badgeSize / 2f,
                    badgeTop + badgeSize / 2f,
                    bgRadius,
                    bgPaint,
                )

                // Clip badge to circle
                val badgeBitmap = Bitmap.createBitmap(badgeSize, badgeSize, Bitmap.Config.ARGB_8888)
                val badgeCanvas = Canvas(badgeBitmap)
                val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                badgeCanvas.drawCircle(badgeSize / 2f, badgeSize / 2f, badgeSize / 2f, clipPaint)
                clipPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                badgeCanvas.drawBitmap(scaledBadge, 0f, 0f, clipPaint)

                canvas.drawBitmap(badgeBitmap, badgeLeft, badgeTop, null)
            }
        } else if (appIconBitmap != null) {
            // No avatar — just show circular app icon
            val scaledApp = Bitmap.createScaledBitmap(appIconBitmap, iconSizePx, iconSizePx, true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(iconSizePx / 2f, iconSizePx / 2f, iconSizePx / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaledApp, 0f, 0f, paint)
        }

        return output
    }

    companion object {
        const val MESSAGES_CHANNEL_ID = "campusmesh_messages"
    }
}
