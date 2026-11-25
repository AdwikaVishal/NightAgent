package com.example.nightagent.journey

import android.content.Context
import com.example.nightagent.journey.model.Journey
import com.example.nightagent.journey.model.JourneyPoint
import com.google.gson.Gson

/**
 * Small, owner-local mirror of the active journey. Firestore offline persistence
 * queues remote writes, but this cache guarantees that journey state created
 * while offline is still available to JourneyEngine immediately after a restart
 * and while the remote query is unavailable.
 *
 * This is not a second Firestore schema: it is a device-local recovery cache
 * keyed by the canonical journey id.
 */
class JourneyLocalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(journey: Journey) {
        prefs.edit()
            .putString(KEY_ACTIVE_JOURNEY, gson.toJson(journey))
            .putString(KEY_JOURNEY_ID, journey.journeyId)
            .putString(KEY_USER_ID, journey.userId)
            .apply()
    }

    fun getActiveJourney(userId: String? = null): Journey? {
        val json = prefs.getString(KEY_ACTIVE_JOURNEY, null) ?: return null
        val journey = runCatching { gson.fromJson(json, Journey::class.java) }.getOrNull() ?: return null
        if (userId != null && journey.userId != userId) return null
        return journey
    }

    /** Owner-scoped lookup. Passing an owner prevents account-switch leakage. */
    fun getJourney(journeyId: String, expectedUserId: String? = null): Journey? =
        getActiveJourney(expectedUserId)?.takeIf { it.journeyId == journeyId }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACTIVE_JOURNEY)
            .remove(KEY_JOURNEY_ID)
            .remove(KEY_USER_ID)
            .remove(KEY_PENDING_UPDATES)
            .remove(KEY_PENDING_LOCATIONS)
            .remove(KEY_PENDING_CREATE_OWNER)
            .apply()
    }

    // ── Pending sync queue ────────────────────────────────────────────────

    data class PendingUpdate(
        val journeyId: String,
        val userId: String,
        val updates: Map<String, Any?>,
        val queuedAt: Long = System.currentTimeMillis()
    )

    fun enqueueUpdate(update: PendingUpdate) {
        val updates = pendingUpdates().filterNot { it.journeyId == update.journeyId } + update
        prefs.edit().putString(KEY_PENDING_UPDATES, gson.toJson(updates)).apply()
    }

    fun pendingUpdates(): List<PendingUpdate> {
        val json = prefs.getString(KEY_PENDING_UPDATES, null) ?: return emptyList()
        return runCatching {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, PendingUpdate::class.java
            ).type
            gson.fromJson<List<PendingUpdate>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun clearPendingUpdates(journeyId: String) {
        prefs.edit().putString(
            KEY_PENDING_UPDATES,
            gson.toJson(pendingUpdates().filterNot { it.journeyId == journeyId })
        ).apply()
    }

    fun enqueueLocation(journeyId: String, userId: String, point: JourneyPoint) {
        val key = KEY_PENDING_LOCATIONS
        val current = runCatching {
            val json = prefs.getString(key, null) ?: "[]"
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, PendingLocation::class.java
            ).type
            gson.fromJson<List<PendingLocation>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
        prefs.edit().putString(key, gson.toJson(current + PendingLocation(journeyId, userId, point))).apply()
    }

    fun pendingLocations(): List<PendingLocation> {
        val json = prefs.getString(KEY_PENDING_LOCATIONS, null) ?: return emptyList()
        return runCatching {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, PendingLocation::class.java
            ).type
            gson.fromJson<List<PendingLocation>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun clearPendingLocations(journeyId: String) {
        prefs.edit().putString(
            KEY_PENDING_LOCATIONS,
            gson.toJson(pendingLocations().filterNot { it.journeyId == journeyId })
        ).apply()
    }

    fun markPendingCreate(userId: String) {
        prefs.edit().putString(KEY_PENDING_CREATE_OWNER, userId).apply()
    }

    fun pendingCreateOwner(): String? = prefs.getString(KEY_PENDING_CREATE_OWNER, null)

    fun clearPendingCreate() = prefs.edit().remove(KEY_PENDING_CREATE_OWNER).apply()

    data class PendingLocation(
        val journeyId: String,
        val userId: String,
        val point: JourneyPoint
    )

    companion object {
        private const val PREFS = "journey_local_cache"
        private const val KEY_ACTIVE_JOURNEY = "active_journey_json"
        private const val KEY_JOURNEY_ID = "active_journey_id"
        private const val KEY_USER_ID = "active_user_id"
        private const val KEY_PENDING_UPDATES = "pending_updates"
        private const val KEY_PENDING_LOCATIONS = "pending_locations"
        private const val KEY_PENDING_CREATE_OWNER = "pending_create_owner"
    }
}
