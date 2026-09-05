package com.campusmesh.call

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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.campusmesh.MainActivity
import com.campusmesh.R
import com.campusmesh.ui.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createCallChannel()
    }

    private fun createCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CALLS_CHANNEL_ID,
                "CampusMesh Voice Calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming voice call alerts from CampusMesh peers"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000, 1000)
                setSound(ringtoneUri, audioAttributes)
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCallNotification(
        callerNodeId: String,
        callerName: String,
        callerAvatarPath: String? = null,
    ) {
        try {
            val callRoute = Routes.call(callerNodeId, callerName, isIncoming = true)

            // Intent to open Call Screen on tap or full screen
            val openCallIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_route", callRoute)
            }
            val openCallPendingIntent = PendingIntent.getActivity(
                context,
                callerNodeId.hashCode(),
                openCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Intent for Decline button
            val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_DECLINE_CALL
            }
            val declinePendingIntent = PendingIntent.getBroadcast(
                context,
                (callerNodeId.hashCode() * 31) + 1,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Intent for Accept button (opens MainActivity with auto_accept)
            val acceptIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_route", callRoute)
                putExtra("auto_accept_call", true)
            }
            val acceptPendingIntent = PendingIntent.getActivity(
                context,
                (callerNodeId.hashCode() * 31) + 2,
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val avatarBitmap = loadCircularAvatar(callerAvatarPath)

            val builder = NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(callerName)
                .setContentText("Incoming CampusMesh voice call...")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openCallPendingIntent)
                .setFullScreenIntent(openCallPendingIntent, true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Decline",
                    declinePendingIntent,
                )
                .addAction(
                    android.R.drawable.ic_menu_call,
                    "Accept",
                    acceptPendingIntent,
                )

            if (avatarBitmap != null) {
                builder.setLargeIcon(avatarBitmap)
            }

            notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, builder.build())
            Timber.i("Displayed incoming call notification for %s", callerName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to show incoming call notification")
        }
    }

    fun cancelIncomingCallNotification() {
        try {
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
            Timber.i("Cancelled incoming call notification")
        } catch (e: Exception) {
            Timber.w(e, "Failed to cancel call notification")
        }
    }

    private fun loadCircularAvatar(avatarPath: String?): Bitmap? {
        val bitmap: Bitmap? = avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
            } else null
        } ?: try {
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_logo)
        } catch (_: Exception) { null }

        if (bitmap == null) return null

        val size = 256
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f

        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        return output
    }

    companion object {
        const val CALLS_CHANNEL_ID = "campusmesh_voice_calls"
        const val INCOMING_CALL_NOTIFICATION_ID = 4001
    }
}
