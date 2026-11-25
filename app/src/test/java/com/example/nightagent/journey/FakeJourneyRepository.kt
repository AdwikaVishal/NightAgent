package com.example.nightagent.journey

import com.example.nightagent.journey.model.*

open class FakeJourneyRepository : JourneyRepository {
    val journeys = mutableMapOf<String, Journey>()
    val locations = mutableMapOf<String, MutableList<JourneyPoint>>()
    val checkIns = mutableMapOf<String, MutableList<CheckInRecord>>()
    val deviations = mutableMapOf<String, MutableList<DeviationEvent>>()

    override suspend fun createJourney(journey: Journey): Result<Unit> = runCatching { journeys[journey.journeyId] = journey }
    override suspend fun getJourney(journeyId: String): Result<Journey?> = runCatching { journeys[journeyId] }
    override suspend fun getActiveJourney(userId: String): Result<Journey?> = runCatching {
        journeys.values.firstOrNull { it.userId == userId && it.status == JourneyStatus.ACTIVE.name }
    }
    override suspend fun updateJourney(journeyId: String, updates: Map<String, Any?>): Result<Unit> = runCatching {
        val j = journeys[journeyId] ?: error("missing")
        journeys[journeyId] = j.copy(
            status = updates["status"] as? String ?: j.status,
            completedAt = (updates["completedAt"] as? Long) ?: j.completedAt,
            actualArrivalTime = (updates["actualArrivalTime"] as? Long) ?: j.actualArrivalTime,
            isActive = (updates["isActive"] as? Boolean) ?: j.isActive,
            updatedAt = (updates["updatedAt"] as? Long) ?: j.updatedAt
        )
    }
    override suspend fun saveLocation(journeyId: String, point: JourneyPoint): Result<Unit> = runCatching {
        locations.getOrPut(journeyId) { mutableListOf() }.add(point)
    }
    override suspend fun getLocationHistory(journeyId: String, limit: Int): Result<List<JourneyPoint>> = runCatching { locations[journeyId].orEmpty().take(limit) }
    override suspend fun saveCheckIn(journeyId: String, checkIn: CheckInRecord): Result<Unit> = runCatching {
        checkIns.getOrPut(journeyId) { mutableListOf() }.add(checkIn)
    }
    override suspend fun updateCheckIn(journeyId: String, checkInId: String, updates: Map<String, Any?>): Result<Unit> = runCatching {
        val list = checkIns[journeyId] ?: error("missing")
        val idx = list.indexOfFirst { it.checkInId == checkInId }
        if (idx >= 0) {
            val c = list[idx]
            list[idx] = c.copy(
                status = updates["status"] as? String ?: c.status,
                respondedAt = (updates["respondedAt"] as? Long) ?: c.respondedAt,
                response = (updates["response"] as? String) ?: c.response,
                escalated = (updates["escalated"] as? Boolean) ?: c.escalated
            )
        }
    }
    override suspend fun getCheckIns(journeyId: String): Result<List<CheckInRecord>> = runCatching { checkIns[journeyId].orEmpty() }
    override suspend fun saveDeviationEvent(journeyId: String, event: DeviationEvent): Result<Unit> = runCatching { deviations.getOrPut(journeyId) { mutableListOf() }.add(event) }
    override suspend fun getJourneyHistory(userId: String, limit: Int): Result<List<Journey>> = runCatching {
        journeys.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }.take(limit)
    }
}
