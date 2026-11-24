package com.example.nightagent.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nightagent.MainActivity
import com.example.nightagent.R

object SOSNotification {

    const val SOS_NOTIFICATION_ID = 9999
    private const val SOS_CHANNEL_ID = "sos_active_channel"

    /**
     * Shows an ongoing persistent notification while an emergency SOS is active,
     * including a direct "Stop SOS" action button that resolves the emergency.
     */
    fun showActiveSOSNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SOS_CHANNEL_ID,
                    "Emergency SOS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Active SOS emergency alert"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val stopIntent = Intent(context, SOSActionReceiver::class.java).apply {
                action = SOSActionReceiver.ACTION_STOP_SOS
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val cancelIntent = Intent(context, SOSActionReceiver::class.java).apply {
                action = SOSActionReceiver.ACTION_CANCEL_SOS
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                2,
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, SOS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("🚨 SOS EMERGENCY ACTIVE")
                .setContentText("Emergency tracking & alerts are active. Tap Stop to resolve or Cancel.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(R.drawable.ic_launcher_foreground, "Stop SOS", stopPendingIntent)
                .addAction(R.drawable.ic_launcher_foreground, "Cancel SOS", cancelPendingIntent)
                .build()

            notificationManager.notify(SOS_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w("SOSNotification", "Failed to show active SOS notification: ${e.message}")
        }
    }

    /**
     * Cancels the ongoing persistent SOS notification once resolved or cancelled.
     */
    fun cancelActiveSOSNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(SOS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w("SOSNotification", "Failed to cancel active SOS notification: ${e.message}")
        }
    }
}
