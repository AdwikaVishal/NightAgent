package com.example.nightagent.evidence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.nightagent.R
import com.example.nightagent.firebase.FirebaseConfig
import com.example.nightagent.sos.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service handling ONLY audio recording and GPS tracking.
 *
 * Android 15 Camera FGS rule: a foreground service with type CAMERA can only be
 * started while the app has a visible Activity AND the user has granted CAMERA.
 * Moving CameraX to the UI layer (EvidenceScreen) eliminates this restriction
 * entirely — this service now declares only microphone|location.
 *
 * Foreground types: MICROPHONE | LOCATION  (no CAMERA)
 */
class EvidenceCaptureService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var evidenceManager: EvidenceManager

    private var sosId: String = ""
    var lastLocation: Location? = null
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        evidenceManager = EvidenceManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            when (intent?.action) {
                ACTION_START -> {
                    sosId = intent.getStringExtra(EXTRA_SOS_ID) ?: System.currentTimeMillis().toString()
                    if (startForegroundCompat()) {
                        beginCapture(sosId)
                    }
                }
                ACTION_STOP -> endCapture()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in EvidenceCaptureService onStartCommand: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { evidenceManager.stopAudioRecording() }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification("Recording audio & location…")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceType = 0
                val hasAudio = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasAudio) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }

                val hasLocation = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                if (hasLocation) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }

                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                    true
                } else {
                    // A service started via startForegroundService must call
                    // startForeground within the timeout. We cannot claim a type
                    // without its permission, so do not start; caller should gate.
                    Log.w(TAG, "No foreground service permissions granted — not starting evidence capture")
                    stopSelf()
                    false
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground compat: ${e.message}")
            // Best effort: avoid FGS timeout if the service was started as FGS.
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            false
        }
    }

    private fun beginCapture(sessionId: String) {
        LocationProvider.getLocation(this) { loc -> lastLocation = loc }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            evidenceManager.startChunkedAudioRecording(sessionId)
        } else {
            Log.w(TAG, "RECORD_AUDIO not granted — skipping audio recording")
        }
    }

    private fun endCapture() {
        runCatching { evidenceManager.stopAudioRecording() }
        val uid = FirebaseConfig.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            Log.w(TAG, "No authenticated user — skipping evidence upload for $sosId")
        } else {
            EvidenceUploadWorker.enqueue(
                context = this,
                sosId = sosId,
                userId = uid,
                latitude = lastLocation?.latitude,
                longitude = lastLocation?.longitude
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Evidence Capture", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎙 Evidence Recording")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG             = "EvidenceCaptureService"
        private const val CHANNEL_ID      = "evidence_capture_channel"
        private const val NOTIFICATION_ID = 3001

        const val ACTION_START = "com.example.nightagent.EVIDENCE_START"
        const val ACTION_STOP  = "com.example.nightagent.EVIDENCE_STOP"
        const val EXTRA_SOS_ID = "sos_id"

        fun start(context: Context, sosId: String) {
            try {
                val hasAudio = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val hasLocation = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                if (!hasAudio && !hasLocation) {
                    Log.w(TAG, "Skipping EvidenceCaptureService start: no audio or location permission")
                    return
                }

                val intent = Intent(context, EvidenceCaptureService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SOS_ID, sosId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start EvidenceCaptureService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, EvidenceCaptureService::class.java).apply {
                    action = ACTION_STOP
                })
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop EvidenceCaptureService: ${e.message}")
            }
        }
    }
}
