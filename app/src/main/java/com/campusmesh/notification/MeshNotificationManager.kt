package com.campusmesh.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.campusmesh.MainActivity
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

            val avatarBitmap = avatarPath?.let { path ->
                val file = File(path)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }

            val notification = NotificationCompat.Builder(context, MESSAGES_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .apply {
                    if (avatarBitmap != null) {
                        setLargeIcon(avatarBitmap)
                    }
                }
                .setContentTitle(senderName)
                .setContentText(messageText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(context).notify(
                senderNodeId.hashCode(),
                notification,
            )
            Timber.i("Posted notification for message from %s", senderName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to post message notification")
        }
    }

    companion object {
        const val MESSAGES_CHANNEL_ID = "campusmesh_messages"
    }
}
