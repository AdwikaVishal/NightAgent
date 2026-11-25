package com.example.nightagent.journey

import android.util.Log
import com.example.nightagent.firebase.FirebaseConfig
import com.google.firebase.auth.FirebaseAuth
import com.example.nightagent.journey.model.CheckInRecord
import com.example.nightagent.journey.model.DeviationEvent
import com.example.nightagent.journey.model.Journey
import com.example.nightagent.journey.model.JourneyPoint
import com.example.nightagent.journey.model.JourneyStatus
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

interface JourneyRepository {
    suspend fun createJourney(journey: Journey): Result<Unit>
    suspend fun getJourney(journeyId: String): Result<Journey?>
    suspend fun getActiveJourney(userId: String): Result<Journey?>
    suspend fun updateJourney(journeyId: String, updates: Map<String, Any?>): Result<Unit>
    suspend fun saveLocation(journeyId: String, point: JourneyPoint): Result<Unit>
    suspend fun getLocationHistory(journeyId: String, limit: Int = 500): Result<List<JourneyPoint>>
    suspend fun saveCheckIn(journeyId: String, checkIn: CheckInRecord): Result<Unit>
    suspend fun updateCheckIn(journeyId: String, checkInId: String, updates: Map<String, Any?>): Result<Unit>
    suspend fun getCheckIns(journeyId: String): Result<List<CheckInRecord>>
    suspend fun saveDeviationEvent(journeyId: String, event: DeviationEvent): Result<Unit>
    suspend fun getJourneyHistory(userId: String, limit: Int = 20): Result<List<Journey>>

    /** True when a local write is waiting for Firestore acknowledgement. */
    suspend fun hasPendingSync(): Boolean = false
    /** Replay queued local writes; returns remote failure if any remain. */
    suspend fun retryPendingSync(userId: String): Result<Unit> = Result.success(Unit)
    /** Drop device-local journey state on sign-out or account switch. */
    fun clearLocalState() = Unit
}

class FirestoreJourneyRepository(
    private val localStore: JourneyLocalStore? = null
) : JourneyRepository {
    private val db get() = FirebaseConfig.firestore

    override suspend fun createJourney(journey: Journey): Result<Unit> = withContext(Dispatchers.IO) {
        // Persist locally before attempting the network so an offline journey is
        // never lost. Firestore itself also queues the write through its cache.
        localStore?.save(journey)
        val remote = runCatching<Unit> {
            db.collection(COL_JOURNEYS).document(journey.journeyId).set(journey).await()
            // Compatibility: maintain safe_walks/{userId} for existing live-tracker/legacy readers.
            val legacy = hashMapOf(
                "userId" to journey.userId,
                "startTime" to journey.startedAt,
                "isActive" to true,
                "journeyId" to journey.journeyId,
                "destinationName" to journey.destinationName,
                "expectedArrivalTime" to journey.expectedArrivalTime
            )
            db.collection(COL_SAFE_WALKS).document(journey.userId).set(legacy, SetOptions.merge()).await()
            Unit
        }
        if (remote.isFailure) {
            localStore?.markPendingCreate(journey.userId)
            Log.w(TAG, "Journey create persisted locally only; remote sync pending", remote.exceptionOrNull())
        } else {
            localStore?.clearPendingCreate()
        }
        // Success here means local durability, not remote confirmation. Callers
        // must consult hasPendingSync() before claiming a remote save.
        Result.success(Unit)
    }

    override suspend fun getJourney(journeyId: String): Result<Journey?> = withContext(Dispatchers.IO) {
        val remote = runCatching {
            db.collection(COL_JOURNEYS).document(journeyId).get().await().toObject(Journey::class.java)
        }
        if (remote.isSuccess && remote.getOrNull() != null) return@withContext remote
        val cached = localStore?.getJourney(journeyId, FirebaseAuth.getInstance().currentUser?.uid)
        if (cached != null) Result.success(cached) else remote
    }

    override suspend fun getActiveJourney(userId: String): Result<Journey?> = withContext(Dispatchers.IO) {
        val remote = runCatching {
            val snap = db.collection(COL_JOURNEYS)
                .whereEqualTo("userId", userId)
                .whereEqualTo("status", JourneyStatus.ACTIVE.name)
                .limit(1)
                .get()
                .await()
            snap.documents.firstOrNull()?.toObject(Journey::class.java)
        }
        val active = remote.getOrNull()
        if (active != null) return@withContext Result.success(active)
        val cached = localStore?.getActiveJourney(userId)
            ?.takeIf { it.statusEnum() == JourneyStatus.ACTIVE }
            ?.takeIf { FirebaseAuth.getInstance().currentUser?.uid == null ||
                FirebaseAuth.getInstance().currentUser?.uid == it.userId }
        if (cached != null) Result.success(cached) else remote
    }

    override suspend fun updateJourney(journeyId: String, updates: Map<String, Any?>): Result<Unit> = withContext(Dispatchers.IO) {
        val cached = localStore?.getJourney(journeyId, FirebaseAuth.getInstance().currentUser?.uid)
        if (cached != null) {
            // Merge every supplied field into the cached entity so partial
            // updates cannot silently drop destination/route/ETA fields.
            val merged = mergeJourneyUpdates(cached, updates)
            localStore.save(merged)
        }
        val remote = runCatching<Unit> {
            db.collection(COL_JOURNEYS).document(journeyId).update(updates).await()
            val status = updates["status"] as? String
            val userId = updates["userId"] as? String
            if (status != null && userId != null) {
                val active = status == JourneyStatus.ACTIVE.name
                db.collection(COL_SAFE_WALKS).document(userId)
                    .set(mapOf("isActive" to active, "journeyId" to journeyId, "status" to status), SetOptions.merge())
                    .await()
            }
            Unit
        }
        if (remote.isFailure) {
            val owner = (updates["userId"] as? String) ?: cached?.userId ?: ""
            if (owner.isNotBlank()) {
                localStore?.enqueueUpdate(
                    JourneyLocalStore.PendingUpdate(journeyId = journeyId, userId = owner, updates = updates)
                )
            }
            Log.w(TAG, "Journey update persisted locally only; remote sync pending", remote.exceptionOrNull())
        } else {
            localStore?.clearPendingUpdates(journeyId)
            // Clear the recovery cache only after the terminal state is remote.
            if (cached != null && (updates["isActive"] as? Boolean) == false) localStore?.clear()
        }
        remote
    }

    private fun mergeJourneyUpdates(journey: Journey, updates: Map<String, Any?>): Journey {
        val gson = com.google.gson.Gson()
        val tree = gson.toJsonTree(journey).asJsonObject
        updates.forEach { (key, value) ->
            if (key == "userId" || key == "journeyId") return@forEach
            tree.add(key, gson.toJsonTree(value))
        }
        return gson.fromJson(tree, Journey::class.java)
    }

    override suspend fun saveLocation(journeyId: String, point: JourneyPoint): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            val journeyRef = db.collection(COL_JOURNEYS).document(journeyId)
            journeyRef.collection(COL_LOCATIONS).add(point).await()
            // Compatibility write for live-tracker: safe_walks/{userId}/locations
            val journeySnap = journeyRef.get().await()
            val userId = journeySnap.getString("userId")
            val status = journeySnap.getString("status")
            if (!userId.isNullOrBlank()) {
                val legacy = hashMapOf(
                    "latitude" to point.latitude,
                    "longitude" to point.longitude,
                    "timestamp" to point.timestamp,
                    "accuracy" to point.accuracy,
                    "journeyId" to journeyId
                )
                db.collection(COL_SAFE_WALKS).document(userId).collection(COL_LOCATIONS).add(legacy).await()
                val legacyState = mutableMapOf<String, Any?>("lastLocationAt" to point.timestamp)
                if (status == JourneyStatus.ACTIVE.name) legacyState["isActive"] = true
                db.collection(COL_SAFE_WALKS).document(userId)
                    .set(legacyState, SetOptions.merge())
                    .await()
            }
            Unit
        }.onFailure {
            val owner = runCatching {
                db.collection(COL_JOURNEYS).document(journeyId).get().await().getString("userId")
            }.getOrNull()
            if (!owner.isNullOrBlank()) {
                localStore?.enqueueLocation(journeyId, owner, point)
            }
            Log.w(TAG, "Location persisted to pending sync queue; remote write unavailable", it)
        }
    }

    override suspend fun getLocationHistory(journeyId: String, limit: Int): Result<List<JourneyPoint>> = withContext(Dispatchers.IO) {
        runCatching {
            db.collection(COL_JOURNEYS).document(journeyId).collection(COL_LOCATIONS)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .limit(limit.toLong())
                .get().await()
                .documents.mapNotNull { it.toObject(JourneyPoint::class.java) }
        }
    }

    override suspend fun saveCheckIn(journeyId: String, checkIn: CheckInRecord): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            db.collection(COL_JOURNEYS).document(journeyId)
                .collection(COL_CHECKINS).document(checkIn.checkInId)
                .set(checkIn).await()
            Unit
        }
    }

    override suspend fun updateCheckIn(journeyId: String, checkInId: String, updates: Map<String, Any?>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            db.collection(COL_JOURNEYS).document(journeyId)
                .collection(COL_CHECKINS).document(checkInId)
                .update(updates).await()
            Unit
        }
    }

    override suspend fun getCheckIns(journeyId: String): Result<List<CheckInRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            db.collection(COL_JOURNEYS).document(journeyId).collection(COL_CHECKINS)
                .orderBy("scheduledAt", Query.Direction.ASCENDING)
                .get().await()
                .documents.mapNotNull { it.toObject(CheckInRecord::class.java) }
        }
    }

    override suspend fun saveDeviationEvent(journeyId: String, event: DeviationEvent): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            db.collection(COL_JOURNEYS).document(journeyId)
                .collection(COL_DEVIATIONS).document(event.eventId)
                .set(event).await()
            Unit
        }
    }

    override suspend fun getJourneyHistory(userId: String, limit: Int): Result<List<Journey>> = withContext(Dispatchers.IO) {
        runCatching {
            db.collection(COL_JOURNEYS)
                .whereEqualTo("userId", userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()
                .documents.mapNotNull { it.toObject(Journey::class.java) }
        }
    }

    override fun clearLocalState() {
        localStore?.clear()
    }

    override suspend fun hasPendingSync(): Boolean = withContext(Dispatchers.IO) {
        localStore?.pendingCreateOwner() != null ||
            localStore?.pendingUpdates()?.isNotEmpty() == true ||
            localStore?.pendingLocations()?.isNotEmpty() == true
    }

    override suspend fun retryPendingSync(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val store = localStore ?: return@withContext Result.success(Unit)
        var firstFailure: Throwable? = null

        if (store.pendingCreateOwner() == userId) {
            val journey = store.getActiveJourney(userId)
            if (journey != null && journey.userId == userId) {
                runCatching {
                    db.collection(COL_JOURNEYS).document(journey.journeyId).set(journey).await()
                }.onSuccess { store.clearPendingCreate() }
                    .onFailure { firstFailure = firstFailure ?: it }
            }
        }

        store.pendingUpdates().filter { it.userId == userId }.forEach { pending ->
            runCatching {
                db.collection(COL_JOURNEYS).document(pending.journeyId).update(pending.updates).await()
            }.onSuccess { store.clearPendingUpdates(pending.journeyId) }
                .onFailure { firstFailure = firstFailure ?: it }
        }

        store.pendingLocations().filter { it.userId == userId }.forEach { pending ->
            runCatching {
                db.collection(COL_JOURNEYS).document(pending.journeyId)
                    .collection(COL_LOCATIONS).add(pending.point).await()
                db.collection(COL_SAFE_WALKS).document(userId).collection(COL_LOCATIONS).add(
                    hashMapOf(
                        "latitude" to pending.point.latitude,
                        "longitude" to pending.point.longitude,
                        "timestamp" to pending.point.timestamp,
                        "accuracy" to pending.point.accuracy,
                        "journeyId" to pending.journeyId
                    )
                ).await()
            }.onSuccess { store.clearPendingLocations(pending.journeyId) }
                .onFailure { firstFailure = firstFailure ?: it }
        }

        firstFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    companion object {
        private const val TAG = "JourneyRepository"
        const val COL_JOURNEYS = "journeys"
        const val COL_LOCATIONS = "locations"
        const val COL_CHECKINS = "checkins"
        const val COL_DEVIATIONS = "deviations"
        const val COL_SAFE_WALKS = "safe_walks" // legacy compatibility
    }
}
