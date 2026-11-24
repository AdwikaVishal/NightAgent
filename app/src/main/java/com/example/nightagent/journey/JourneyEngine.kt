package com.example.nightagent.journey

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.nightagent.firebase.AuthManager
import com.example.nightagent.journey.model.*
import com.example.nightagent.sos.SOSManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class JourneyEngine internal constructor(
    private val appContext: Context,
    private val repository: JourneyRepository = FirestoreJourneyRepository(),
    private val locationTracker: LocationTracker = LocationTracker(appContext),
    private val runtimeStore: JourneyRuntimeStore = JourneyRuntimeStore(appContext),
    private val notifier: JourneyNotifier = DefaultJourneyNotifier(appContext),
    private val checkInWork: CheckInWorkScheduler = DefaultCheckInWorkScheduler(appContext),
    private val sosEscalator: (String) -> Unit = { reason -> SOSManager.triggerSOS(appContext, reason = reason) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val userIdProvider: () -> String? = { runCatching { AuthManager.getCurrentUserId() }.getOrNull() },
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    externalScope: CoroutineScope? = null
) {
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(JourneyUiState())
    val state: StateFlow<JourneyUiState> = _state.asStateFlow()

    private var trackingJob: Job? = null
    private var checkInJob: Job? = null
    private var arrivalJob: Job? = null
    private var deviationDetector: RouteDeviationDetector = RouteDeviationDetector()
    private var lastPoint: JourneyPoint? = null
    private var escalationDedup = mutableSetOf<String>()

    fun hasActiveJourney(): Boolean = _state.value.journey?.statusEnum() == JourneyStatus.ACTIVE

    suspend fun restoreActiveJourney() {
        val uid = userIdProvider() ?: runtimeStore.getActiveUserId() ?: return
        val active = repository.getActiveJourney(uid).getOrNull() ?: run {
            if (runtimeStore.getActiveJourneyId() != null) runtimeStore.clearAll()
            return
        }
        _state.update { it.copy(journey = active, error = null) }
        deviationDetector = RouteDeviationDetector(active.deviationThresholdMeters, DEFAULT_DEVIATION_SUSTAIN_COUNT)
        startMonitoring(active)
        resumeCheckIns(active)
    }

    private fun resumeCheckIns(journey: Journey) {
        checkInJob?.cancel()
        checkInJob = scope.launch {
            val checkIns = repository.getCheckIns(journey.journeyId).getOrNull().orEmpty()
            val pending = checkIns.filter { it.statusEnum() == CheckInStatus.PENDING }
                .minByOrNull { it.dueAt }
            val now = nowMs()
            if (pending != null) {
                _state.update { it.copy(pendingCheckIn = pending) }
                if (now >= pending.dueAt) {
                    markCheckInMissed(journey.journeyId, pending.checkInId)
                } else {
                    runtimeStore.setNextCheckIn(journey.journeyId, pending.checkInId, pending.dueAt)
                    notifier.showCheckIn(journey.journeyId, pending.checkInId)
                    enqueueCheckInWatchdog(journey.journeyId, pending.checkInId, pending.dueAt - now)
                    delay((pending.dueAt - now).coerceAtLeast(0L))
                    val latest = repository.getCheckIns(journey.journeyId).getOrNull()?.find { it.checkInId == pending.checkInId }
                    if (latest?.statusEnum() == CheckInStatus.PENDING) {
                        markCheckInMissed(journey.journeyId, pending.checkInId)
                    }
                }
            }
            scheduleNextCheckInLoop(journey.journeyId)
        }
    }

    suspend fun startJourney(
        destinationName: String,
        destinationLat: Double,
        destinationLng: Double,
        expectedArrivalTime: Long,
        checkInIntervalMinutes: Int = DEFAULT_CHECKIN_INTERVAL_MIN,
        checkInResponseTimeoutSeconds: Int = DEFAULT_CHECKIN_TIMEOUT_SEC,
        routePoints: List<RoutePoint> = emptyList()
    ): Result<Journey> = withContext(dispatcher) {
        if (hasActiveJourney()) return@withContext Result.failure(IllegalStateException("A journey is already active"))
        val uid = userIdProvider() ?: runtimeStore.getActiveUserId() ?: "anonymous_user"
        val existingActive = repository.getActiveJourney(uid).getOrNull()
        if (existingActive != null) {
            _state.update { it.copy(journey = existingActive, error = null) }
            deviationDetector = RouteDeviationDetector(existingActive.deviationThresholdMeters, DEFAULT_DEVIATION_SUSTAIN_COUNT)
            startMonitoring(existingActive)
            resumeCheckIns(existingActive)
            return@withContext Result.failure(IllegalStateException("A journey is already active"))
        }
        if (destinationName.isBlank()) return@withContext Result.failure(IllegalArgumentException("Destination is required"))
        if (destinationLat !in -90.0..90.0 || destinationLng !in -180.0..180.0) return@withContext Result.failure(IllegalArgumentException("Invalid destination"))
        if (expectedArrivalTime <= nowMs()) return@withContext Result.failure(IllegalArgumentException("Expected arrival must be in the future"))
        if (!locationTracker.hasLocationPermission()) return@withContext Result.failure(SecurityException("Location permission not granted"))

        _state.update { it.copy(isLoading = true, error = null) }
        val startPoint = locationTracker.getCurrentPoint()
            ?: return@withContext Result.failure(IllegalStateException("Unable to get current location. Enable GPS and try again."))
        val now = nowMs()
        val journey = Journey(
            journeyId = UUID.randomUUID().toString(),
            userId = uid,
            startLatitude = startPoint.latitude,
            startLongitude = startPoint.longitude,
            destinationLatitude = destinationLat,
            destinationLongitude = destinationLng,
            destinationName = destinationName.trim(),
            expectedArrivalTime = expectedArrivalTime,
            startedAt = now,
            status = JourneyStatus.ACTIVE.name,
            createdAt = now,
            updatedAt = now,
            checkInIntervalMinutes = checkInIntervalMinutes.coerceIn(1, 60),
            checkInResponseTimeoutSeconds = checkInResponseTimeoutSeconds.coerceIn(30, 600),
            routePoints = routePoints,
            isActive = true
        )
        val create = repository.createJourney(journey)
        if (create.isFailure) {
            _state.update { it.copy(isLoading = false, error = create.exceptionOrNull()?.message) }
            return@withContext Result.failure(create.exceptionOrNull() ?: Exception("Failed to create journey"))
        }
        repository.saveLocation(journey.journeyId, startPoint).onFailure {
            Log.w(TAG, "Failed to persist journey start location", it)
        }
        runtimeStore.setActiveJourney(journey.journeyId, uid)
        deviationDetector = RouteDeviationDetector(journey.deviationThresholdMeters, DEFAULT_DEVIATION_SUSTAIN_COUNT)
        lastPoint = startPoint
        escalationDedup.clear()
        _state.update { it.copy(journey = journey, isLoading = false, error = null, lastLocation = startPoint) }
        startMonitoring(journey)
        scheduleNextCheckIn(journey)
        Result.success(journey)
    }

    private fun startMonitoring(journey: Journey) {
        trackingJob?.cancel()
        arrivalJob?.cancel()
        trackingJob = scope.launch {
            locationTracker.reset()
            locationTracker.locationUpdates(JourneyConfig()).catch { e ->
                Log.e(TAG, "Location updates failed", e)
                _state.update { it.copy(error = e.message) }
            }.collect { point ->
                handleLocation(journey.journeyId, point)
            }
        }
        arrivalJob = scope.launch { monitorArrival(journey.journeyId) }
    }

    private suspend fun handleLocation(journeyId: String, point: JourneyPoint) {
        val j = _state.value.journey ?: repository.getJourney(journeyId).getOrNull() ?: return
        if (j.statusEnum() != JourneyStatus.ACTIVE) return
        lastPoint = point
        _state.update { it.copy(lastLocation = point) }
        repository.saveLocation(journeyId, point).onFailure {
            Log.w(TAG, "Failed to persist live location for $journeyId", it)
        }

        // Deviation detection
        if (j.routePoints.size >= 2) {
            val res = deviationDetector.evaluate(point, j.routePoints)
            _state.update { it.copy(distanceFromRouteMeters = res.distanceMeters) }
            if (res.isDeviated) {
                val eventId = "dev_${journeyId}_${point.timestamp / 60000}" // dedupe per minute
                if (escalationDedup.add(eventId)) {
                    repository.saveDeviationEvent(journeyId, DeviationEvent(
                        eventId = eventId, journeyId = journeyId, detectedAt = nowMs(),
                        latitude = point.latitude, longitude = point.longitude,
                        distanceFromRouteMeters = res.distanceMeters, reason = res.reason
                    )).onFailure { Log.w(TAG, "Failed to persist deviation event", it) }
                    notifier.showAlert("Route deviation detected", "You appear off the planned route (${res.distanceMeters.toInt()}m). Check in or request help.")
                    escalate(JourneyEventType.ROUTE_DEVIATION, "Route deviation: ${res.distanceMeters.toInt()}m from planned route")
                }
            }
        }

        // Arrival proximity
        val distToDest = LocationTracker.distanceMeters(point.latitude, point.longitude, j.destinationLatitude, j.destinationLongitude)
        _state.update { it.copy(distanceToDestinationMeters = distToDest) }
        if (distToDest <= ARRIVAL_PROXIMITY_METERS) {
            _state.update { it.copy(arrivalNearby = true) }
        }
    }

    private fun scheduleNextCheckIn(journey: Journey) {
        checkInJob?.cancel()
        checkInJob = scope.launch { scheduleNextCheckInLoop(journey.journeyId) }
    }

    private suspend fun scheduleNextCheckInLoop(journeyId: String) {
        while (scope.isActive && _state.value.journey?.statusEnum() == JourneyStatus.ACTIVE) {
            val current = _state.value.journey ?: repository.getJourney(journeyId).getOrNull() ?: break
            if (current.statusEnum() != JourneyStatus.ACTIVE) break
            val intervalMs = current.checkInIntervalMinutes.coerceAtLeast(1) * 60_000L
            val timeoutMs = current.checkInResponseTimeoutSeconds.coerceAtLeast(30) * 1000L
            delay(intervalMs)
            val active = _state.value.journey ?: break
            if (active.statusEnum() != JourneyStatus.ACTIVE) break
            // Don't stack check-ins if one is already pending
            val existingPending = repository.getCheckIns(active.journeyId).getOrNull().orEmpty()
                .any { it.statusEnum() == CheckInStatus.PENDING }
            if (existingPending) {
                // Let watchdog/restore handle it; wait a bit before checking again
                delay(timeoutMs)
                continue
            }
            val checkInId = UUID.randomUUID().toString()
            val now = nowMs()
            val record = CheckInRecord(
                checkInId = checkInId, journeyId = active.journeyId,
                scheduledAt = now, dueAt = now + timeoutMs, status = CheckInStatus.PENDING.name
            )
            repository.saveCheckIn(active.journeyId, record).onFailure {
                Log.w(TAG, "Failed to persist check-in ${record.checkInId}", it)
            }
            runtimeStore.setNextCheckIn(active.journeyId, checkInId, record.dueAt)
            _state.update { it.copy(pendingCheckIn = record) }
            notifier.showCheckIn(active.journeyId, checkInId)
            enqueueCheckInWatchdog(active.journeyId, checkInId, timeoutMs)

            delay(timeoutMs)
            val latest = repository.getCheckIns(active.journeyId).getOrNull()?.find { it.checkInId == checkInId }
            if (latest?.statusEnum() == CheckInStatus.PENDING) {
                markCheckInMissed(active.journeyId, checkInId)
            }
        }
    }

    internal suspend fun markCheckInMissed(journeyId: String, checkInId: String) {
        val current = _state.value.journey ?: repository.getJourney(journeyId).getOrNull() ?: return
        if (current.statusEnum() != JourneyStatus.ACTIVE) return
        val latest = repository.getCheckIns(journeyId).getOrNull()?.find { it.checkInId == checkInId }
        if (latest != null && latest.statusEnum() != CheckInStatus.PENDING) {
            if (_state.value.pendingCheckIn?.checkInId == checkInId) {
                runtimeStore.clearCheckIn()
                notifier.cancelCheckIn()
                _state.update { it.copy(pendingCheckIn = null) }
            }
            return
        }
        val key = "missed_$checkInId"
        if (!escalationDedup.add(key)) return
        repository.updateCheckIn(journeyId, checkInId, mapOf("status" to CheckInStatus.MISSED.name, "escalated" to true, "respondedAt" to nowMs()))
            .onFailure { Log.w(TAG, "Failed to persist missed check-in", it) }
        runtimeStore.clearCheckIn()
        checkInWork.cancel(checkInId)
        notifier.cancelCheckIn()
        if (_state.value.pendingCheckIn?.checkInId == checkInId) {
            _state.update { it.copy(pendingCheckIn = null) }
        }
        notifier.showAlert("Check-in missed", "A safety check-in was missed during your journey.")
        escalate(JourneyEventType.MISSED_CHECKIN, "Missed safety check-in")
    }

    suspend fun acknowledgeCheckIn(journeyId: String, checkInId: String) {
        val j = _state.value.journey ?: repository.getJourney(journeyId).getOrNull() ?: return
        if (j.statusEnum() != JourneyStatus.ACTIVE) return
        val checkIn = repository.getCheckIns(journeyId).getOrNull()?.find { it.checkInId == checkInId } ?: return
        if (checkIn.statusEnum() != CheckInStatus.PENDING) return
        repository.updateCheckIn(journeyId, checkInId, mapOf("status" to CheckInStatus.ACKNOWLEDGED.name, "respondedAt" to nowMs(), "response" to "SAFE"))
            .onFailure { Log.w(TAG, "Failed to persist acknowledged check-in", it) }
        runtimeStore.clearCheckIn()
        checkInWork.cancel(checkInId)
        notifier.cancelCheckIn()
        if (_state.value.pendingCheckIn?.checkInId == checkInId) {
            _state.update { it.copy(pendingCheckIn = null) }
        }
    }

    suspend fun requestHelpOnCheckIn(journeyId: String, checkInId: String) {
        val j = _state.value.journey ?: repository.getJourney(journeyId).getOrNull() ?: return
        if (j.statusEnum() != JourneyStatus.ACTIVE) return
        val checkIn = repository.getCheckIns(journeyId).getOrNull()?.find { it.checkInId == checkInId } ?: return
        if (checkIn.statusEnum() != CheckInStatus.PENDING) return
        repository.updateCheckIn(journeyId, checkInId, mapOf("status" to CheckInStatus.HELP_REQUESTED.name, "respondedAt" to nowMs(), "response" to "HELP", "escalated" to true))
            .onFailure { Log.w(TAG, "Failed to persist help check-in", it) }
        runtimeStore.clearCheckIn()
        checkInWork.cancel(checkInId)
        notifier.cancelCheckIn()
        if (_state.value.pendingCheckIn?.checkInId == checkInId) {
            _state.update { it.copy(pendingCheckIn = null) }
        }
        escalate(JourneyEventType.USER_REQUESTED_HELP, "User requested help during check-in")
    }

    private suspend fun monitorArrival(journeyId: String) {
        while (scope.isActive) {
            delay(30_000)
            val j = _state.value.journey ?: repository.getJourney(journeyId).getOrNull() ?: return
            if (j.statusEnum() != JourneyStatus.ACTIVE) return
            val now = nowMs()
            val graceMs = j.arrivalGraceMinutes.coerceAtLeast(0) * 60_000L
            if (now > j.expectedArrivalTime + graceMs) {
                val near = (_state.value.distanceToDestinationMeters ?: Float.MAX_VALUE) <= ARRIVAL_PROXIMITY_METERS
                if (!near) {
                    val key = "delay_$journeyId"
                    if (escalationDedup.add(key)) {
                        notifier.showAlert("Arrival delayed", "You have not reached your destination within the expected time.")
                        escalate(JourneyEventType.ARRIVAL_DELAY, "Arrival delayed past expected time")
                    }
                }
            }
        }
    }

    private fun escalate(type: JourneyEventType, reason: String) {
        Log.w(TAG, "Journey escalation: $type - $reason")
        _state.update { it.copy(lastEscalation = type to reason) }
        try {
            sosEscalator("$type: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "SOS escalation failed", e)
        }
    }

    suspend fun completeJourney(reason: String = "Arrived safely"): Result<Unit> = withContext(dispatcher) {
        val j = _state.value.journey ?: return@withContext Result.failure(IllegalStateException("No active journey"))
        finishJourney(j, JourneyStatus.COMPLETED, reason)
    }

    suspend fun cancelJourney(reason: String = "Cancelled by user"): Result<Unit> = withContext(dispatcher) {
        val j = _state.value.journey ?: return@withContext Result.failure(IllegalStateException("No active journey"))
        finishJourney(j, JourneyStatus.CANCELLED, reason)
    }

    suspend fun markEmergency(reason: String = "Emergency"): Result<Unit> = withContext(dispatcher) {
        val j = _state.value.journey ?: return@withContext Result.failure(IllegalStateException("No active journey"))
        finishJourney(j, JourneyStatus.EMERGENCY, reason)
    }

    private suspend fun finishJourney(j: Journey, status: JourneyStatus, reason: String): Result<Unit> {
        if (j.statusEnum() != JourneyStatus.ACTIVE) {
            return Result.success(Unit)
        }
        val now = nowMs()
        val updates = mutableMapOf<String, Any?>(
            "status" to status.name,
            "updatedAt" to now,
            "completedAt" to now,
            "isActive" to false,
            "userId" to j.userId
        )
        if (status == JourneyStatus.COMPLETED) updates["actualArrivalTime"] = now
        val res = repository.updateJourney(j.journeyId, updates)
        stopMonitoring()
        cancelCheckInWatchdogs(j.journeyId)
        runtimeStore.clearAll()
        notifier.cancelAll()
        _state.update {
            it.copy(
                journey = j.copy(
                    status = status.name,
                    completedAt = now,
                    isActive = false,
                    actualArrivalTime = if (status == JourneyStatus.COMPLETED) now else j.actualArrivalTime
                ),
                pendingCheckIn = null
            )
        }
        Log.i(TAG, "Journey ${j.journeyId} finished: $status ($reason)")
        return res
    }

    fun stopMonitoring() {
        trackingJob?.cancel(); trackingJob = null
        checkInJob?.cancel(); checkInJob = null
        arrivalJob?.cancel(); arrivalJob = null
        locationTracker.reset()
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    private fun enqueueCheckInWatchdog(journeyId: String, checkInId: String, timeoutMs: Long) {
        checkInWork.enqueue(journeyId, checkInId, timeoutMs)
    }

    private fun cancelCheckInWatchdogs(journeyId: String) {
        checkInWork.cancelJourney(journeyId)
    }

    companion object {
        private const val TAG = "JourneyEngine"
        @Volatile private var INSTANCE: JourneyEngine? = null
        fun getInstance(context: Context): JourneyEngine = INSTANCE ?: synchronized(this) {
            INSTANCE ?: JourneyEngine(context.applicationContext).also { INSTANCE = it }
        }
    }
}

interface JourneyNotifier {
    fun showCheckIn(journeyId: String, checkInId: String)
    fun cancelCheckIn()
    fun showAlert(title: String, message: String)
    fun cancelAll()
}

interface CheckInWorkScheduler {
    fun enqueue(journeyId: String, checkInId: String, timeoutMs: Long)
    fun cancel(checkInId: String)
    fun cancelJourney(journeyId: String)
}

internal class DefaultJourneyNotifier(private val context: Context) : JourneyNotifier {
    override fun showCheckIn(journeyId: String, checkInId: String) = JourneyNotificationHelper.showCheckInNotification(context, journeyId, checkInId)
    override fun cancelCheckIn() = JourneyNotificationHelper.cancelCheckIn(context)
    override fun showAlert(title: String, message: String) = JourneyNotificationHelper.showAlert(context, title, message)
    override fun cancelAll() = JourneyNotificationHelper.cancelAll(context)
}

internal class DefaultCheckInWorkScheduler(private val context: Context) : CheckInWorkScheduler {
    override fun enqueue(journeyId: String, checkInId: String, timeoutMs: Long) {
        val data = androidx.work.Data.Builder()
            .putString(CheckInWatchdogWorker.KEY_JOURNEY_ID, journeyId)
            .putString(CheckInWatchdogWorker.KEY_CHECKIN_ID, checkInId)
            .build()
        val req = androidx.work.OneTimeWorkRequestBuilder<CheckInWatchdogWorker>()
            .setInitialDelay(timeoutMs + 5000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("journey_checkin_$journeyId")
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("checkin_$checkInId", androidx.work.ExistingWorkPolicy.REPLACE, req)
    }
    override fun cancel(checkInId: String) { androidx.work.WorkManager.getInstance(context).cancelUniqueWork("checkin_$checkInId") }
    override fun cancelJourney(journeyId: String) { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("journey_checkin_$journeyId") }
}

data class JourneyUiState(
    val journey: Journey? = null,
    val lastLocation: JourneyPoint? = null,
    val pendingCheckIn: CheckInRecord? = null,
    val distanceToDestinationMeters: Float? = null,
    val distanceFromRouteMeters: Float? = null,
    val arrivalNearby: Boolean = false,
    val lastEscalation: Pair<JourneyEventType, String>? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
