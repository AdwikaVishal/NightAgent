package com.example.nightagent.journey

import com.example.nightagent.firebase.FirebaseConfig
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
}

class FirestoreJourneyRepository : JourneyRepository {
    private val db get() = FirebaseConfig.firestore

    override suspend fun createJourney(journey: Journey): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
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
    }

    override suspend fun getJourney(journeyId: String): Result<Journey?> = withContext(Dispatchers.IO) {
        runCatching {
            db.collection(COL_JOURNEYS).document(journeyId).get().await().toObject(Journey::class.java)
        }
    }

    override suspend fun getActiveJourney(userId: String): Result<Journey?> = withContext(Dispatchers.IO) {
        runCatching {
            val snap = db.collection(COL_JOURNEYS)
                .whereEqualTo("userId", userId)
                .whereEqualTo("status", JourneyStatus.ACTIVE.name)
                .limit(1)
                .get()
                .await()
            snap.documents.firstOrNull()?.toObject(Journey::class.java)
        }
    }

    override suspend fun updateJourney(journeyId: String, updates: Map<String, Any?>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
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

    companion object {
        const val COL_JOURNEYS = "journeys"
        const val COL_LOCATIONS = "locations"
        const val COL_CHECKINS = "checkins"
        const val COL_DEVIATIONS = "deviations"
        const val COL_SAFE_WALKS = "safe_walks" // legacy compatibility
    }
}
