package com.example.nightagent.journey

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class JourneyTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: JourneyEngine
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = JourneyEngine.getInstance(applicationContext)
        JourneyNotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> refreshNotification()
        }

        val notif = JourneyNotificationHelper.trackingNotification(this, engine.state.value.journey?.journeyId ?: "", "Tracking your journey...")
        try {
            startForegroundCompat(notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (monitorJob?.isActive == true) return START_STICKY
        monitorJob = serviceScope.launch {
            if (!engine.hasActiveJourney()) {
                engine.restoreActiveJourney()
            }
            if (!engine.hasActiveJourney()) {
                Log.w(TAG, "No active journey, stopping service")
                stopForegroundSafely(); stopSelf()
                return@launch
            }
            engine.state.collect { st ->
                val j = st.journey ?: run {
                    stopForegroundSafely(); stopSelf()
                    return@collect
                }
                val text = buildString {
                    append("To ${j.destinationName}")
                    st.distanceToDestinationMeters?.let { append(" • ${"%.0f".format(it)}m left") }
                    st.pendingCheckIn?.takeIf { it.statusEnum() == com.example.nightagent.journey.model.CheckInStatus.PENDING }?.let { append(" • Check-in pending") }
                }
                val n = JourneyNotificationHelper.trackingNotification(this@JourneyTrackingService, j.journeyId, text)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(JourneyNotificationHelper.NOTIF_TRACKING_ID, n)
                if (j.statusEnum() != com.example.nightagent.journey.model.JourneyStatus.ACTIVE) {
                    stopForegroundSafely(); stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun refreshNotification() {
        val st = engine.state.value
        val j = st.journey ?: return
        val n = JourneyNotificationHelper.trackingNotification(this, j.journeyId, "Tracking to ${j.destinationName}")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(JourneyNotificationHelper.NOTIF_TRACKING_ID, n)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(JourneyNotificationHelper.NOTIF_TRACKING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(JourneyNotificationHelper.NOTIF_TRACKING_ID, notification)
        }
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If journey still active, service should have been START_STICKY; do not clear state here.
    }

    companion object {
        private const val TAG = "JourneyTrackingService"
        const val ACTION_STOP = "com.example.nightagent.journey.TRACK_STOP"
        const val ACTION_REFRESH = "com.example.nightagent.journey.TRACK_REFRESH"

        fun start(context: Context) {
            val intent = Intent(context, JourneyTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JourneyTrackingService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
