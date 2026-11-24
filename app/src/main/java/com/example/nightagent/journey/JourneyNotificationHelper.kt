package com.example.nightagent.journey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.nightagent.MainActivity
import com.example.nightagent.R

object JourneyNotificationHelper {
    const val CHANNEL_TRACKING = "journey_tracking"
    const val CHANNEL_CHECKIN = "journey_checkin"
    const val CHANNEL_ALERTS = "journey_alerts"

    const val NOTIF_TRACKING_ID = 4101
    const val NOTIF_CHECKIN_ID = 4102
    const val NOTIF_ALERT_ID = 4103

    const val ACTION_CHECKIN_SAFE = "com.example.nightagent.journey.CHECKIN_SAFE"
    const val ACTION_CHECKIN_HELP = "com.example.nightagent.journey.CHECKIN_HELP"
    const val ACTION_END_JOURNEY = "com.example.nightagent.journey.END_JOURNEY"
    const val EXTRA_JOURNEY_ID = "journey_id"
    const val EXTRA_CHECKIN_ID = "checkin_id"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TRACKING, "Journey Tracking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing Safe Journey tracking"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CHECKIN, "Journey Check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Safety check-in prompts"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Journey Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Journey safety alerts"
        })
    }

    fun trackingNotification(context: Context, journeyId: String, content: String): Notification {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "safewalk")
            },
            pendingFlags()
        )
        val endIntent = actionPendingIntent(context, 11, ACTION_END_JOURNEY, journeyId)
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Safe Journey active")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "End journey", endIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showCheckInNotification(context: Context, journeyId: String, checkInId: String) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "safewalk")
            },
            pendingFlags()
        )
        val safeIntent = actionPendingIntent(context, 21, ACTION_CHECKIN_SAFE, journeyId, checkInId)
        val helpIntent = actionPendingIntent(context, 22, ACTION_CHECKIN_HELP, journeyId, checkInId)
        val notif = NotificationCompat.Builder(context, CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Safety check-in")
            .setContentText("Are you safe? Tap to confirm.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your Safe Journey check-in is due. Confirm you're safe, or request help."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "I'm Safe", safeIntent)
            .addAction(0, "I Need Help", helpIntent)
            .build()
        notify(context, NOTIF_CHECKIN_ID, notif)
    }

    fun showAlert(context: Context, title: String, message: String, id: Int = NOTIF_ALERT_ID) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context, 30,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "safewalk")
            },
            pendingFlags()
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        notify(context, id, notif)
    }

    fun cancelCheckIn(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_CHECKIN_ID)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_CHECKIN_ID)
        NotificationManagerCompat.from(context).cancel(NOTIF_ALERT_ID)
    }

    private fun actionPendingIntent(context: Context, req: Int, action: String, journeyId: String, checkInId: String? = null): PendingIntent {
        val intent = Intent(context, JourneyActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_JOURNEY_ID, journeyId)
            if (checkInId != null) putExtra(EXTRA_CHECKIN_ID, checkInId)
        }
        return PendingIntent.getBroadcast(context, req, intent, pendingFlags())
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS may be missing on Android 13+
        }
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }
}
