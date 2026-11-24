package com.example.nightagent.journey

import com.example.nightagent.journey.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class JourneyRepositoryContractTest {
    private val repo = FakeJourneyRepository()

    @Test
    fun createAndGetActiveJourney() = runTest {
        val j = Journey(journeyId = "j1", userId = "u1", status = JourneyStatus.ACTIVE.name, isActive = true, startedAt = 1, createdAt = 1, updatedAt = 1)
        assertTrue(repo.createJourney(j).isSuccess)
        val active = repo.getActiveJourney("u1").getOrNull()
        assertEquals("j1", active?.journeyId)
    }

    @Test
    fun saveAndRetrieveLocations() = runTest {
        repo.createJourney(Journey(journeyId = "j1", userId = "u1"))
        val p = JourneyPoint(1.0, 2.0, accuracy = 5f, timestamp = 10)
        assertTrue(repo.saveLocation("j1", p).isSuccess)
        val hist = repo.getLocationHistory("j1").getOrNull()
        assertEquals(1, hist?.size)
        assertEquals(1.0, hist?.first()?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun checkInLifecycle() = runTest {
        repo.createJourney(Journey(journeyId = "j1", userId = "u1"))
        val c = CheckInRecord(checkInId = "c1", journeyId = "j1", status = CheckInStatus.PENDING.name)
        repo.saveCheckIn("j1", c)
        repo.updateCheckIn("j1", "c1", mapOf("status" to CheckInStatus.ACKNOWLEDGED.name, "response" to "SAFE"))
        val got = repo.getCheckIns("j1").getOrNull()?.first()
        assertEquals(CheckInStatus.ACKNOWLEDGED.name, got?.status)
        assertEquals("SAFE", got?.response)
    }

    @Test
    fun journeyHistoryOrderedByCreatedAtDesc() = runTest {
        repo.createJourney(Journey(journeyId = "j1", userId = "u1", createdAt = 1))
        repo.createJourney(Journey(journeyId = "j2", userId = "u1", createdAt = 2))
        val hist = repo.getJourneyHistory("u1").getOrNull().orEmpty()
        assertEquals(listOf("j2", "j1"), hist.map { it.journeyId })
    }
}
