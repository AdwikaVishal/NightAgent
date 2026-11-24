package com.example.nightagent.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.nightagent.model.EmergencySession
import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Repository responsible for persisting emergency sessions and location updates.
 * - Writes to Firestore under `sos_alerts/{userId}/sessions/{sessionId}`.
 * - Writes live tracking updates to subcollection `location_updates`.
 * - Falls back to local storage (in-memory + SharedPreferences) on failures or unauthenticated users.
 * - Retries failed syncs with exponential backoff.
 * - Reloads unfinished sessions across process death.
 */
class EmergencySessionRepository(
    private val firestore: FirebaseFirestore? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val db: FirebaseFirestore?
        get() = firestore ?: try {
            FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            null
        }

    private val repositoryScope = CoroutineScope(dispatcher + SupervisorJob())
    private val gson = Gson()

    internal fun cancelRetries() {
        repositoryScope.coroutineContext[Job]?.cancelChildren()
    }

    @Volatile
    private var inMemorySession: EmergencySession? = null

    companion object {
        private const val TAG = "EmergencySessionRepo"
        private const val PREFS_NAME = "emergency_session_prefs"
        private const val KEY_SAVED_SESSION = "active_session_json"
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Persists or updates the session locally and attempts Firestore sync.
     * Retries with exponential backoff on failure without dropping the session.
     */
    suspend fun saveSession(
        context: Context,
        session: EmergencySession,
        message: String = ""
    ): EmergencySession = withContext(dispatcher) {
        // 1. Save locally (in-memory + SharedPreferences) first
        inMemorySession = session
        saveLocalSession(context, session)

        // 2. Check user auth
        if (session.userId.isBlank() || session.userId == "anonymous_user") {
            val unauthSession = session.copy(
                failureReason = "User not authenticated to Firestore",
                updatedAt = System.currentTimeMillis()
            )
            inMemorySession = unauthSession
            saveLocalSession(context, unauthSession)
            Log.w(TAG, "Saved session locally: user not authenticated")
            return@withContext unauthSession
        }

        // 3. Attempt Firestore sync
        val syncResult = syncToFirestore(session, message)
        if (syncResult.isSuccess) {
            val syncedSession = session.copy(
                failureReason = null,
                updatedAt = System.currentTimeMillis()
            )
            inMemorySession = syncedSession
            saveLocalSession(context, syncedSession)
            Log.d(TAG, "Session synced to Firestore: ${session.id}")
            return@withContext syncedSession
        } else {
            val failureMsg = syncResult.exceptionOrNull()?.message ?: "Firestore write failed"
            val failedSession = session.copy(
                failureReason = failureMsg,
                updatedAt = System.currentTimeMillis()
            )
            inMemorySession = failedSession
            saveLocalSession(context, failedSession)
            Log.e(TAG, "Firestore sync failed: $failureMsg. Scheduling retry.")
            scheduleRetry(context, failedSession, message)
            return@withContext failedSession
        }
    }

    /**
     * Idempotently writes session to Firestore at sos_alerts/{userId}/sessions/{sessionId}.
     * Note: The document id is sessionId, providing an idempotency guarantee on Firestore writes.
     * Before creating/writing a new doc, callers and repository use session.id (and session.idempotencyKey)
     * so repeat sync attempts or network retries merge into the exact same document without duplicates.
     */
    private suspend fun syncToFirestore(
        session: EmergencySession,
        message: String
    ): Result<Unit> = withContext(dispatcher) {
        val database = db ?: return@withContext Result.failure(IllegalStateException("Firestore instance unavailable"))

        val sessionData = hashMapOf<String, Any?>(
            "id" to session.id,
            "sessionId" to session.id,
            "userId" to session.userId,
            "state" to session.state.name,
            "triggerType" to session.triggerType.name,
            "startLat" to session.startLat,
            "startLng" to session.startLng,
            "startTime" to session.createdAt,
            "createdAt" to session.createdAt,
            "updatedAt" to session.updatedAt,
            "resolvedAt" to session.resolvedAt,
            "idempotencyKey" to session.idempotencyKey,
            "failureReason" to session.failureReason,
            "message" to message
        )

        try {
            database.collection("sos_alerts")
                .document(session.userId)
                .collection("sessions")
                .document(session.id)
                .set(sessionData, SetOptions.merge())
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Appends live location updates to the session subcollection:
     * sos_alerts/{userId}/sessions/{sessionId}/location_updates
     */
    suspend fun addLocationUpdate(
        userId: String,
        sessionId: String,
        latitude: Double,
        longitude: Double
    ): Result<Unit> = withContext(dispatcher) {
        val database = db ?: return@withContext Result.failure(IllegalStateException("Firestore unavailable"))
        if (userId.isBlank() || userId == "anonymous_user" || sessionId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid userId or sessionId"))
        }

        val data = hashMapOf(
            "lat" to latitude,
            "lng" to longitude,
            "time" to System.currentTimeMillis()
        )

        try {
            database.collection("sos_alerts")
                .document(userId)
                .collection("sessions")
                .document(sessionId)
                .collection("location_updates")
                .add(data)
                .await()

            Log.d(TAG, "Location update saved to Firestore")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save location update: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Retries syncing the session with exponential backoff.
     * Retries until the session with that id has synced (failureReason == null).
     * Stops only if a different session replaced it or the coroutine scope is cancelled.
     * Final states (RESOLVED, CANCELLED) are still retried so Firestore accurately updates.
     */
    private fun scheduleRetry(
        context: Context,
        session: EmergencySession,
        message: String
    ) {
        repositoryScope.launch {
            var delayMs = 1000L
            val maxDelayMs = 30000L
            var attempt = 0

            while (isActive) {
                delay(delayMs)
                attempt++

                val current = getLocalSession(context) ?: break
                // Stop retrying only if a different session has replaced it or it was already successfully synced
                if (current.id != session.id || current.failureReason == null) {
                    break
                }

                val syncResult = syncToFirestore(current, message)
                if (syncResult.isSuccess) {
                    val resolved = current.copy(failureReason = null, updatedAt = System.currentTimeMillis())
                    inMemorySession = resolved
                    saveLocalSession(context, resolved)
                    Log.d(TAG, "Retry successful on attempt $attempt for session ${session.id}")
                    break
                } else {
                    Log.w(TAG, "Retry attempt $attempt failed for session ${session.id}")
                    delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                }
            }
        }
    }

    /**
     * Loads an unfinished emergency session (INITIATED, ACTIVE, or ACKNOWLEDGED)
     * from local storage after process death.
     */
    fun getUnfinishedSession(context: Context): EmergencySession? {
        val session = getLocalSession(context) ?: inMemorySession ?: return null
        return if (session.state in setOf(SOSState.INITIATED, SOSState.ACTIVE, SOSState.ACKNOWLEDGED)) {
            session
        } else {
            null
        }
    }

    /**
     * Returns the local session if its failureReason is not null,
     * regardless of state (including RESOLVED and CANCELLED), provided its userId is not "anonymous_user".
     */
    fun getUnsyncedSession(context: Context): EmergencySession? {
        val session = getLocalSession(context) ?: inMemorySession ?: return null
        return if (session.failureReason != null && session.userId.isNotBlank() && session.userId != "anonymous_user") {
            session
        } else {
            null
        }
    }

    /**
     * Saves session to SharedPreferences in JSON format.
     */
    fun saveLocalSession(context: Context, session: EmergencySession) {
        inMemorySession = session
        val json = toJson(session)
        getPrefs(context).edit().putString(KEY_SAVED_SESSION, json).apply()
    }

    /**
     * Reads session from SharedPreferences.
     */
    fun getLocalSession(context: Context): EmergencySession? {
        val json = getPrefs(context).getString(KEY_SAVED_SESSION, null) ?: return null
        return fromJson(json)
    }

    /**
     * Re-assigns the userId of an unauthenticated or anonymous session to the newly authenticated userId,
     * and attempts to sync it to Firestore.
     */
    suspend fun reassignAnonymousSession(context: Context, newUserId: String): EmergencySession? = withContext(dispatcher) {
        if (newUserId.isBlank() || newUserId == "anonymous_user") return@withContext null

        val current = inMemorySession ?: getLocalSession(context) ?: return@withContext null
        if (current.userId == "anonymous_user" || current.userId.isBlank()) {
            val updated = current.copy(
                userId = newUserId,
                updatedAt = System.currentTimeMillis()
            )
            Log.d(TAG, "Reassigning session ${updated.id} to new userId: $newUserId")
            saveSession(context, updated)
        } else {
            null
        }
    }

    /**
     * Clears local active session storage.
     */
    fun clearLocalSession(context: Context) {
        inMemorySession = null
        getPrefs(context).edit().remove(KEY_SAVED_SESSION).apply()
    }

    private fun toJson(session: EmergencySession): String {
        return gson.toJson(session)
    }

    private fun fromJson(jsonStr: String): EmergencySession? {
        return try {
            gson.fromJson(jsonStr, EmergencySession::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
