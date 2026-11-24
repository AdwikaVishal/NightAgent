package com.example.nightagent.sos.engine

import com.example.nightagent.model.EmergencySession
import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Pure Kotlin SOS state machine engine.
 * No Android imports to ensure JVM-unit-testability.
 * Thread-safe transitions across all legal emergency states.
 */
class SOSEngine {

    private val _sessionState = MutableStateFlow<EmergencySession?>(null)
    val sessionState: StateFlow<EmergencySession?> = _sessionState.asStateFlow()

    private val lock = Any()

    companion object {
        const val DEFAULT_MAX_DURATION_MILLIS = 60 * 60 * 1000L // 60 minutes default safety net

        private val LEGAL_TRANSITIONS: Map<SOSState, Set<SOSState>> = mapOf(
            SOSState.IDLE to setOf(SOSState.INITIATED),
            SOSState.INITIATED to setOf(SOSState.ACTIVE, SOSState.CANCELLED),
            SOSState.ACTIVE to setOf(SOSState.ACKNOWLEDGED, SOSState.RESOLVED, SOSState.CANCELLED),
            SOSState.ACKNOWLEDGED to setOf(SOSState.RESOLVED),
            SOSState.RESOLVED to emptySet(),
            SOSState.CANCELLED to emptySet()
        )
    }

    /**
     * Creates a session in IDLE state.
     */
    fun createIdleSession(
        userId: String,
        triggerType: TriggerType,
        startLat: Double? = null,
        startLng: Double? = null,
        sessionId: String = UUID.randomUUID().toString(),
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<EmergencySession> = synchronized(lock) {
        val current = _sessionState.value
        if (current != null && current.state in setOf(SOSState.INITIATED, SOSState.ACTIVE, SOSState.ACKNOWLEDGED)) {
            return Result.success(current)
        }

        val now = System.currentTimeMillis()
        val session = EmergencySession(
            id = sessionId,
            userId = userId,
            state = SOSState.IDLE,
            triggerType = triggerType,
            startLat = startLat,
            startLng = startLng,
            createdAt = now,
            updatedAt = now,
            resolvedAt = null,
            idempotencyKey = idempotencyKey,
            failureReason = null
        )
        _sessionState.value = session
        Result.success(session)
    }

    /**
     * Initiates an emergency session.
     * If an existing session is INITIATED, ACTIVE, or ACKNOWLEDGED, returns the existing session
     * (idempotent; does not spawn duplicate sessions).
     */
    fun initiateSession(
        userId: String,
        triggerType: TriggerType,
        startLat: Double? = null,
        startLng: Double? = null,
        sessionId: String = UUID.randomUUID().toString(),
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<EmergencySession> = synchronized(lock) {
        val current = _sessionState.value
        if (current != null && current.state in setOf(SOSState.INITIATED, SOSState.ACTIVE, SOSState.ACKNOWLEDGED)) {
            return Result.success(current)
        }

        val now = System.currentTimeMillis()
        val newSession = EmergencySession(
            id = sessionId,
            userId = userId,
            state = SOSState.INITIATED,
            triggerType = triggerType,
            startLat = startLat,
            startLng = startLng,
            createdAt = now,
            updatedAt = now,
            resolvedAt = null,
            idempotencyKey = idempotencyKey,
            failureReason = null
        )
        _sessionState.value = newSession
        Result.success(newSession)
    }

    /**
     * Executes a legal transition to [targetState].
     * Returns Result.failure for illegal transitions, never throws.
     */
    fun transitionTo(targetState: SOSState, reason: String? = null): Result<EmergencySession> = synchronized(lock) {
        val current = _sessionState.value
            ?: return Result.failure(IllegalStateException("No active session to transition to $targetState"))

        val allowedTransitions = LEGAL_TRANSITIONS[current.state] ?: emptySet()
        if (targetState !in allowedTransitions) {
            return Result.failure(
                IllegalStateException("Illegal state transition from ${current.state} to $targetState")
            )
        }

        val now = System.currentTimeMillis()
        val updated = current.copy(
            state = targetState,
            updatedAt = now,
            resolvedAt = if (targetState == SOSState.RESOLVED || targetState == SOSState.CANCELLED) now else current.resolvedAt,
            failureReason = reason ?: current.failureReason
        )
        _sessionState.value = updated
        Result.success(updated)
    }

    fun activate(): Result<EmergencySession> = transitionTo(SOSState.ACTIVE)

    fun acknowledge(): Result<EmergencySession> = transitionTo(SOSState.ACKNOWLEDGED)

    fun resolve(): Result<EmergencySession> = transitionTo(SOSState.RESOLVED)

    fun cancel(reason: String? = null): Result<EmergencySession> = transitionTo(SOSState.CANCELLED, reason)

    /**
     * Safety net: auto-resolves or cancels the session if it has exceeded [maxDurationMillis].
     * Returns Result.success(session) if auto-resolved/cancelled, or Result.success(null) if not expired.
     */
    fun checkMaxDuration(
        maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Result<EmergencySession?> = synchronized(lock) {
        val current = _sessionState.value ?: return Result.success(null)
        if (current.state !in setOf(SOSState.ACTIVE, SOSState.ACKNOWLEDGED, SOSState.INITIATED)) {
            return Result.success(null)
        }

        if (currentTimeMillis - current.createdAt >= maxDurationMillis) {
            val autoResolveReason = "Auto-resolved: exceeded maximum duration of ${maxDurationMillis / 60000} minutes"
            val result = if (current.state == SOSState.INITIATED) {
                cancel(autoResolveReason)
            } else {
                transitionTo(SOSState.RESOLVED, autoResolveReason)
            }
            return result.map { it }
        }
        Result.success(null)
    }

    fun updateUserId(newUserId: String): EmergencySession? = synchronized(lock) {
        val current = _sessionState.value ?: return null
        val updated = current.copy(
            userId = newUserId,
            updatedAt = System.currentTimeMillis()
        )
        _sessionState.value = updated
        updated
    }

    fun updateCoordinates(latitude: Double, longitude: Double): EmergencySession? = synchronized(lock) {
        val current = _sessionState.value ?: return null
        val updated = current.copy(
            startLat = current.startLat ?: latitude,
            startLng = current.startLng ?: longitude,
            updatedAt = System.currentTimeMillis()
        )
        _sessionState.value = updated
        updated
    }

    fun updateFailureReason(reason: String?): EmergencySession? = synchronized(lock) {
        val current = _sessionState.value ?: return null
        val updated = current.copy(
            failureReason = reason,
            updatedAt = System.currentTimeMillis()
        )
        _sessionState.value = updated
        updated
    }

    fun restoreSession(session: EmergencySession) = synchronized(lock) {
        _sessionState.value = session
    }

    fun currentSession(): EmergencySession? = synchronized(lock) {
        _sessionState.value
    }

    fun reset() = synchronized(lock) {
        _sessionState.value = null
    }
}
