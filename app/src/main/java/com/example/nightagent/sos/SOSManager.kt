package com.example.nightagent.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.nightagent.R
import com.example.nightagent.contacts.ContactManager
import com.example.nightagent.guardian.repository.GuardianRepositoryImpl
import com.example.nightagent.journey.JourneyEngine
import com.example.nightagent.journey.model.JourneyPoint
import com.example.nightagent.model.EmergencySession
import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import com.example.nightagent.notifications.NotificationHelper
import com.example.nightagent.repository.EmergencySessionRepository
import com.example.nightagent.sos.engine.SOSEngine
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*

/**
 * SOSManager orchestrates the emergency workflow:
 * - Backed by pure Kotlin SOSEngine state machine.
 * - Manages side effects (audio alarm, SMS notification, Firestore session & live location updates).
 * - Side effects are strictly tied to the session state and stop when RESOLVED or CANCELLED.
 * - Idempotent: avoids duplicate triggers while a session is active.
 * - PII and coordinate logging stripped for privacy and security.
 */
object SOSManager {

    private const val TAG = "SOSManager"
    private const val LOCATION_INTERVAL_MS = 5000L

    val engine = SOSEngine()
    val repository = EmergencySessionRepository()

    /** Configurable maximum duration in minutes before auto-resolving as a safety net */
    var maxDurationMinutes: Long = 60L

    private var applicationContext: Context? = null
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var onCompleteCallback: (() -> Unit)? = null
    private var isLocationLoopRunning = false
    private var trackingRunnable: Runnable? = null
    private var autoResolveRunnable: Runnable? = null
    private var acknowledgmentListener: ListenerRegistration? = null
    private var journeyLocationJob: Job? = null
    private var lastJourneyLocationForwardedAt = 0L
    private val notifiedGuardianSessionIds = mutableSetOf<String>()

    fun currentSession(): EmergencySession? = engine.currentSession()

    /**
     * Backward-compatible entry point used by journey escalations. The reason is
     * included in the alert message; it is never stored as a credential.
     */
    fun triggerSOS(
        context: Context,
        reason: String? = null,
        onSOSComplete: () -> Unit = {}
    ) {
        triggerSOSInternal(context, TriggerType.MANUAL, reason, onSOSComplete)
    }

    /**
     * Main SOS trigger handler with TriggerType.
     */
    fun triggerSOS(
        context: Context,
        triggerType: TriggerType = TriggerType.MANUAL,
        onSOSComplete: () -> Unit = {}
    ) {
        triggerSOSInternal(context, triggerType, null, onSOSComplete)
    }

    private fun triggerSOSInternal(
        context: Context,
        triggerType: TriggerType,
        reason: String?,
        onSOSComplete: () -> Unit
    ) {
        SafetySettings.init(context)

        // Primary duplicate-SOS guard; the state machine below is the second.
        val existingSession = engine.currentSession()
        if (existingSession != null && existingSession.state in setOf(
                SOSState.INITIATED,
                SOSState.ACTIVE,
                SOSState.ACKNOWLEDGED
            )
        ) {
            Log.d(TAG, "SOS trigger ignored: existing session ${existingSession.id} in state ${existingSession.state}")
            return
        }

        this.onCompleteCallback = onSOSComplete
        applicationContext = context.applicationContext

        val userId = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: "anonymous_user"

        // 2. Initiate session in SOSEngine
        val initResult = engine.initiateSession(userId = userId, triggerType = triggerType)
        val session = initResult.getOrElse {
            Log.e(TAG, "Failed to initiate SOS session: ${it.message}")
            return
        }

        Log.d(TAG, "SOS session initiated: ${session.id} (trigger: $triggerType)")
        Toast.makeText(context, "SOS Triggered!", Toast.LENGTH_SHORT).show()

        // Show ongoing persistent notification with Stop action
        SOSNotification.showActiveSOSNotification(context)

        // 3. Side-effect: Alarm (if not in silent mode)
        if (!SafetySettings.silentMode.value) {
            playAlarm(context)
        }

        // 4. Save session locally + firestore asynchronously (SMS/alarm still work even if Firestore fails)
        scope.launch(Dispatchers.IO) {
            repository.saveSession(context, session)
        }

        // 5. Fetch location, compose SMS, send SMS, activate session, start tracking
        Log.d(TAG, "Requesting initial location for SOS")
        LocationProvider.getLocation(context) { location ->
            val currentState = engine.currentSession()?.state
            // If session was cancelled while awaiting location, abort side effects
            if (currentState == SOSState.CANCELLED || currentState == SOSState.RESOLVED) {
                Log.d(TAG, "Session ended before location received")
                return@getLocation
            }

            if (location != null) {
                engine.updateCoordinates(location.latitude, location.longitude)
            }

            val reasonLine = if (reason.isNullOrBlank()) "" else "\nReason: ${reason.trim()}"
            val message = if (location != null) {
                """🚨 SOS ALERT!

I need help.$reasonLine

My current location:
https://maps.google.com/?q=${location.latitude},${location.longitude}"""
            } else {
                """🚨 SOS ALERT!

I need help.$reasonLine"""
            }

            // Transition engine to ACTIVE
            val activateResult = engine.activate()
            if (activateResult.isSuccess) {
                val activeSession = activateResult.getOrThrow()
                scope.launch(Dispatchers.IO) {
                    repository.saveSession(context, activeSession, message)
                }
                // Start listening for guardian acknowledgment on Firestore
                listenForAcknowledgment(context, activeSession.userId, activeSession.id)
                // Request guardian push delivery through the canonical repository.
                // Delivery is never claimed unless the backend confirms it.
                notifyGuardians(context, activeSession)
            }

            // Start live tracking loop driven by session state
            startLiveTracking(context)

            // Send emergency SMS
            sendEmergencySMS(context, message)
        }

        // Stop alarm audio after 15s to protect speaker/hearing, but session remains ACTIVE
        mainHandler.postDelayed({
            stopAlarm()
        }, 15000)

        // Schedule auto-resolve safety net
        scheduleAutoResolve(context, maxDurationMinutes * 60 * 1000L)
    }

    /**
     * Acknowledge the active emergency session.
     */
    fun acknowledge(context: Context? = null) {
        val result = engine.acknowledge()
        result.onSuccess { session ->
            Log.d(TAG, "Emergency session acknowledged: ${session.id}")
            context?.let { ctx ->
                scope.launch(Dispatchers.IO) {
                    repository.saveSession(ctx, session)
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to acknowledge: ${it.message}")
        }
    }

    /**
     * Resolve the active emergency session.
     */
    fun resolve(context: Context? = null) {
        val result = engine.resolve()
        result.onSuccess { session ->
            Log.d(TAG, "Emergency session resolved: ${session.id}")
            stopSideEffects(context)
            context?.let { ctx ->
                scope.launch(Dispatchers.IO) {
                    repository.saveSession(ctx, session)
                }
                Toast.makeText(ctx, "SOS Resolved", Toast.LENGTH_SHORT).show()
            }
            onCompleteCallback?.invoke()
            onCompleteCallback = null
        }.onFailure {
            Log.w(TAG, "Failed to resolve: ${it.message}")
        }
    }

    /**
     * Cancel the active or initiated emergency session.
     */
    fun cancel(context: Context? = null, reason: String? = null) {
        val result = engine.cancel(reason)
        result.onSuccess { session ->
            Log.d(TAG, "Emergency session cancelled: ${session.id} (reason=$reason)")
            stopSideEffects(context)
            context?.let { ctx ->
                scope.launch(Dispatchers.IO) {
                    repository.saveSession(ctx, session)
                }
                Toast.makeText(ctx, "SOS Cancelled", Toast.LENGTH_SHORT).show()
            }
            onCompleteCallback?.invoke()
            onCompleteCallback = null
        }.onFailure {
            Log.w(TAG, "Failed to cancel: ${it.message}")
        }
    }

    /**
     * Called when the user signs in or authenticates.
     * If the active engine session is under "anonymous_user", it updates the session's userId,
     * syncs it to Firestore, and attaches the guardian acknowledgment listener.
     * Otherwise, falls back to reassigning any local saved session in the repository.
     */
    fun onAuthenticated(context: Context, uid: String) {
        if (uid.isBlank() || uid == "anonymous_user") return
        applicationContext = context.applicationContext

        val current = engine.currentSession()
        if (current != null && (current.userId == "anonymous_user" || current.userId.isBlank())) {
            Log.d(TAG, "onAuthenticated: updating live engine session ${current.id} to uid: $uid")
            val updated = engine.updateUserId(uid)
            if (updated != null) {
                scope.launch(Dispatchers.IO) {
                    repository.saveSession(context, updated)
                }
                if (updated.state == SOSState.ACTIVE || updated.state == SOSState.ACKNOWLEDGED) {
                    listenForAcknowledgment(context, uid, updated.id)
                }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                repository.reassignAnonymousSession(context, uid)
            }
        }
    }

    /**
     * Resyncs any locally stored session that has a pending failureReason.
     * Runs on app start without restarting alarms or live tracking for finished sessions.
     */
    fun resyncPendingSession(context: Context) {
        val unsynced = repository.getUnsyncedSession(context)
        if (unsynced != null) {
            Log.d(TAG, "resyncPendingSession: re-saving unsynced session ${unsynced.id} (state: ${unsynced.state})")
            scope.launch(Dispatchers.IO) {
                repository.saveSession(context, unsynced)
            }
        }
    }

    /**
     * Restores an unfinished session after process death:
     * - Re-syncs the restored session to Firestore (or retries if failureReason was set).
     * - Reschedules the auto-resolve safety net using the remaining time (createdAt + maxDuration - now).
     * - If already expired, calls engine.checkMaxDuration(), stops side effects, and saves the final state.
     * - If INITIATED, transitions to ACTIVE, starts tracking, attaches acknowledgment listener, and saves.
     */
    fun restoreUnfinishedSession(context: Context) {
        resyncPendingSession(context)

        val unfinished = repository.getUnfinishedSession(context)
        if (unfinished != null) {
            Log.d(TAG, "Restoring unfinished SOS session: ${unfinished.id} (state: ${unfinished.state})")
            engine.restoreSession(unfinished)
            applicationContext = context.applicationContext

            val maxDurationMs = maxDurationMinutes * 60 * 1000L
            val elapsed = System.currentTimeMillis() - unfinished.createdAt
            val remainingMs = maxDurationMs - elapsed

            if (remainingMs <= 0) {
                Log.w(TAG, "Restored session has already exceeded max duration; expiring now")
                val expiredResult = engine.checkMaxDuration(maxDurationMs)
                val expiredSession = expiredResult.getOrNull()
                if (expiredSession != null) {
                    stopSideEffects(context)
                    scope.launch(Dispatchers.IO) {
                        repository.saveSession(context, expiredSession)
                    }
                    onCompleteCallback?.invoke()
                    onCompleteCallback = null
                    return
                }
            }

            // If session was restored in INITIATED, transition it to ACTIVE
            val sessionToRun = if (unfinished.state == SOSState.INITIATED) {
                val activateResult = engine.activate()
                activateResult.getOrDefault(unfinished)
            } else {
                unfinished
            }

            // Still active/acknowledged and not expired
            if (sessionToRun.state == SOSState.ACTIVE || sessionToRun.state == SOSState.ACKNOWLEDGED) {
                NotificationHelper.show(context, "Resuming SOS Tracking")
                SOSNotification.showActiveSOSNotification(context)
                startLiveTracking(context)
                listenForAcknowledgment(context, sessionToRun.userId, sessionToRun.id)
                notifyGuardians(context, sessionToRun)
            }

            // Reschedule auto-resolve safety net with remaining time
            scheduleAutoResolve(context, remainingMs)

            // Re-sync session to Firestore (especially if failureReason was set or write was pending)
            scope.launch(Dispatchers.IO) {
                repository.saveSession(context, sessionToRun)
            }
        }
    }

    private fun playAlarm(context: Context) {
        try {
            NotificationHelper.show(context, "SOS Triggered")
            mediaPlayer = MediaPlayer.create(context, R.raw.alarm_sos)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm: ${e.message}")
        }
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }

    private fun stopSideEffects(context: Context? = null) {
        stopAlarm()
        stopLiveTracking()
        cancelAutoResolve()
        stopAcknowledgmentListener()

        val ctx = context ?: applicationContext
        ctx?.let {
            SOSNotification.cancelActiveSOSNotification(it)
            try {
                com.example.nightagent.evidence.EvidenceCaptureService.stop(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop EvidenceCaptureService: ${e.message}")
            }
        }
    }


    /**
     * Reset internal state for unit tests. Cancels coroutines, clears pending work, and removes listeners.
     */
    internal fun resetForTesting() {
        // Cancel any ongoing coroutines in the manager scope
        scope.coroutineContext[Job]?.cancelChildren()
        // Cancel any repository retry loops
        repository.cancelRetries()
        // Remove pending auto‑resolve runnable
        cancelAutoResolve()
        // Remove acknowledgment listener if registered
        stopAcknowledgmentListener()
        // Clear the onComplete callback
        onCompleteCallback = null
        // Clear stored context and media player
        applicationContext = null
        mediaPlayer = null
        isLocationLoopRunning = false
        journeyLocationJob = null
        lastJourneyLocationForwardedAt = 0L
        notifiedGuardianSessionIds.clear()
    }

    private fun scheduleAutoResolve(context: Context, delayMillis: Long) {
        cancelAutoResolve()
        val runnable = Runnable {
            val session = engine.currentSession()
            if (session != null && session.state in setOf(SOSState.INITIATED, SOSState.ACTIVE, SOSState.ACKNOWLEDGED)) {
                Log.w(TAG, "Safety net triggered: checking max duration for session ${session.id}")
                val expiredResult = engine.checkMaxDuration(maxDurationMinutes * 60 * 1000L)
                val expiredSession = expiredResult.getOrNull()
                if (expiredSession != null) {
                    stopSideEffects(context)
                    scope.launch(Dispatchers.IO) {
                        repository.saveSession(context, expiredSession)
                    }
                    val msg = if (expiredSession.state == SOSState.CANCELLED) "SOS Cancelled (Auto)" else "SOS Resolved (Auto)"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onCompleteCallback?.invoke()
                    onCompleteCallback = null
                }
            }
        }
        autoResolveRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun cancelAutoResolve() {
        autoResolveRunnable?.let { mainHandler.removeCallbacks(it) }
        autoResolveRunnable = null
    }

    private fun listenForAcknowledgment(context: Context, userId: String, sessionId: String) {
        stopAcknowledgmentListener()
        if (userId.isBlank() || userId == "anonymous_user" || sessionId.isBlank()) return

        try {
            val db = FirebaseFirestore.getInstance()
            acknowledgmentListener = db.collection("sos_alerts")
                .document(userId)
                .collection("sessions")
                .document(sessionId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Guardian acknowledgment listener error: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val acknowledgedBy = snapshot.getString("acknowledgedBy")
                        val remoteState = snapshot.getString("state")
                        if (!acknowledgedBy.isNullOrBlank() || remoteState == SOSState.ACKNOWLEDGED.name) {
                            val current = engine.currentSession()
                            if (current?.state == SOSState.ACTIVE) {
                                Log.d(TAG, "Guardian acknowledged SOS session $sessionId (by: $acknowledgedBy)")
                                acknowledge(context)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach guardian acknowledgment listener: ${e.message}")
        }
    }

    private fun stopAcknowledgmentListener() {
        acknowledgmentListener?.remove()
        acknowledgmentListener = null
    }

    /**
     * Requests guardian push delivery only for guardians linked to a Firebase
     * account. The repository returns only confirmed recipients; failures are
     * logged and SMS remains the fallback.
     */
    private fun notifyGuardians(context: Context, session: EmergencySession) {
        if (session.id.isBlank() || session.userId.isBlank() || session.userId == "anonymous_user") return
        if (!notifiedGuardianSessionIds.add(session.id)) {
            Log.d(TAG, "Guardian notification already requested for session ${session.id}")
            return
        }
        val appCtx = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runCatching {
                val guardianRepo = GuardianRepositoryImpl(appCtx)
                val guardians = when (val result = guardianRepo.getGuardians()) {
                    is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success -> result.guardians
                    else -> emptyList()
                }
                val guardianUids = guardians.mapNotNull { guardian ->
                    val uid = guardian.userId?.trim().orEmpty()
                    uid.takeIf { it.isNotBlank() && it != "anonymous_user" && it != guardian.guardianId }
                }.distinct()
                if (guardianUids.isEmpty()) {
                    Log.w(TAG, "No linked guardian accounts available for push delivery; SMS fallback remains active")
                    return@runCatching
                }
                val primaryUid = guardians.firstOrNull { it.isPrimary }?.userId?.trim()
                    ?.takeIf { it in guardianUids }
                val location = if (session.startLat != null && session.startLng != null) {
                    session.startLat to session.startLng
                } else {
                    null
                }
                when (val result = guardianRepo.requestSOSNotification(
                    sessionId = session.id,
                    userId = session.userId,
                    guardianIds = guardianUids,
                    primaryGuardianId = primaryUid,
                    location = location,
                    timestamp = session.createdAt
                )) {
                    is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                        Log.i(TAG, "Guardian push confirmed for ${result.guardians.size} recipient(s)")
                    is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                        Log.w(TAG, "Guardian push not confirmed: ${result.message}")
                }
            }.onFailure { Log.w(TAG, "Guardian notification request failed: ${it.message}") }
        }
    }

    private fun startLiveTracking(context: Context) {
        if (isLocationLoopRunning) return

        // An active journey already owns one FusedLocationProvider update stream.
        // Relay it into the SOS session instead of opening a second GPS poller.
        val journeyEngine = JourneyEngine.getInstance(context.applicationContext)
        if (journeyEngine.hasActiveJourney()) {
            startJourneyLocationRelay(journeyEngine)
            return
        }

        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            Log.w(TAG, "SOS live tracking skipped: location permission not granted")
            return
        }

        isLocationLoopRunning = true

        val runnable = object : Runnable {
            override fun run() {
                val session = engine.currentSession()
                // Stop loop if session is RESOLVED or CANCELLED or null
                if (session == null || session.state !in setOf(SOSState.ACTIVE, SOSState.ACKNOWLEDGED)) {
                    stopLiveTracking()
                    return
                }

                LocationProvider.getLocation(context) { location ->
                    if (location != null) {
                        scope.launch(Dispatchers.IO) {
                            repository.addLocationUpdate(
                                userId = session.userId,
                                sessionId = session.id,
                                latitude = location.latitude,
                                longitude = location.longitude
                            )
                        }
                        Log.d(TAG, "Live location update submitted [REDACTED]")
                    }
                }

                mainHandler.postDelayed(this, LOCATION_INTERVAL_MS)
            }
        }

        trackingRunnable = runnable
        mainHandler.post(runnable)
    }

    /**
     * Forwards fixes already collected by the active journey into the SOS
     * session so there is exactly one location provider and no duplicate polling.
     */
    private fun startJourneyLocationRelay(journeyEngine: JourneyEngine) {
        isLocationLoopRunning = true
        journeyLocationJob?.cancel()
        lastJourneyLocationForwardedAt = 0L
        journeyLocationJob = scope.launch(Dispatchers.IO) {
            journeyEngine.state.collect { state ->
                val session = engine.currentSession()
                val point: JourneyPoint = state.lastLocation ?: return@collect
                if (session == null || session.state !in setOf(SOSState.ACTIVE, SOSState.ACKNOWLEDGED)) return@collect
                val now = System.currentTimeMillis()
                if (now - lastJourneyLocationForwardedAt < LOCATION_INTERVAL_MS) return@collect
                if (point.timestamp <= lastJourneyLocationForwardedAt) return@collect
                lastJourneyLocationForwardedAt = now
                runCatching {
                    repository.addLocationUpdate(
                        userId = session.userId,
                        sessionId = session.id,
                        latitude = point.latitude,
                        longitude = point.longitude
                    )
                }.onFailure { Log.w(TAG, "Failed to forward journey location to SOS session: ${it.message}") }
                Log.d(TAG, "Journey location forwarded to SOS session [REDACTED]")
            }
        }
    }

    private fun stopLiveTracking() {
        isLocationLoopRunning = false
        trackingRunnable?.let { mainHandler.removeCallbacks(it) }
        trackingRunnable = null
        journeyLocationJob?.cancel()
        journeyLocationJob = null
    }

    private fun sendEmergencySMS(context: Context, message: String) {
        Toast.makeText(context, "Sending SOS SMS...", Toast.LENGTH_SHORT).show()

        val contacts = ContactManager.getContacts(context)
        Log.d(TAG, "Emergency contacts loaded: count=${contacts.size}")

        if (contacts.isEmpty()) {
            Toast.makeText(context, "No emergency contacts found", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "No emergency contacts configured")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "SEND_SMS permission not granted")
            Toast.makeText(context, "SMS permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        val smsManager = SmsManager.getDefault()
        var anySmsSent = false
        var anySmsFailed = false

        for (contact in contacts) {
            val phone = contact.phone.trim()
            if (phone.isBlank()) {
                anySmsFailed = true
                continue
            }

            try {
                val parts = smsManager.divideMessage(message)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(phone, null, message, null, null)
                } else {
                    val sentIntents = arrayListOf<android.app.PendingIntent>()
                    val deliveryIntents = arrayListOf<android.app.PendingIntent>()
                    for (partIndex in parts.indices) {
                        sentIntents.add(
                            android.app.PendingIntent.getBroadcast(
                                context,
                                partIndex,
                                android.content.Intent("SMS_SENT_$partIndex"),
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        )
                        deliveryIntents.add(
                            android.app.PendingIntent.getBroadcast(
                                context,
                                partIndex,
                                android.content.Intent("SMS_DELIVERED_$partIndex"),
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        )
                    }
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
                }
                anySmsSent = true
                Log.d(TAG, "Emergency SMS dispatched successfully")
            } catch (e: Exception) {
                anySmsFailed = true
                Log.e(TAG, "Emergency SMS dispatch failed: ${e.message}")
            }
        }

        if (anySmsSent) {
            Toast.makeText(context, "SOS SMS sent", Toast.LENGTH_SHORT).show()
        }

        if (anySmsFailed && !anySmsSent) {
            Toast.makeText(context, "Failed to send SOS SMS", Toast.LENGTH_SHORT).show()
        }
    }
}
