package com.example.nightagent.journey

import android.content.Context
import com.example.nightagent.journey.model.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyEngineTest {

    private lateinit var context: Context
    private lateinit var repo: FakeJourneyRepository
    private lateinit var locationTracker: LocationTracker
    private lateinit var runtimeStore: JourneyRuntimeStore
    private lateinit var notifier: FakeNotifier
    private lateinit var work: FakeCheckInWork
    private lateinit var sosReasons: MutableList<String>
    private lateinit var locationFlow: MutableSharedFlow<JourneyPoint>
    private val engines = mutableListOf<JourneyEngine>()
    private var now = 1_000_000L

    @After
    fun tearDown() {
        engines.clear()
    }

    @Before
    fun setup() {
        engines.clear()
        context = mockk(relaxed = true)
        repo = FakeJourneyRepository()
        runtimeStore = mockk(relaxed = true)
        every { runtimeStore.getActiveJourneyId() } returns null
        every { runtimeStore.getActiveUserId() } returns "u1"
        every { runtimeStore.getNextCheckInDueAt() } returns 0L
        every { runtimeStore.getNextCheckInId() } returns null
        every { runtimeStore.getNextCheckInJourneyId() } returns null

        locationFlow = MutableSharedFlow(extraBufferCapacity = 64)
        locationTracker = mockk(relaxed = true)
        every { locationTracker.hasLocationPermission() } returns true
        coEvery { locationTracker.getCurrentPoint() } returns JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = now)
        every { locationTracker.locationUpdates(any()) } returns locationFlow
        every { locationTracker.reset() } just Runs

        notifier = FakeNotifier()
        work = FakeCheckInWork()
        sosReasons = mutableListOf()
    }

    /**
     * JourneyEngine runs infinite monitoring loops on the supplied dispatcher.
     * Cancel them inside runTest's body so its advanceUntilIdle finalization
     * does not keep scheduling delayed tasks forever.
     */
    private fun testEngine(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            engines.forEach { it.stopMonitoring() }
        }
    }

    private fun TestScope.engine(): JourneyEngine = JourneyEngine(
        appContext = context,
        repository = repo,
        locationTracker = locationTracker,
        runtimeStore = runtimeStore,
        notifier = notifier,
        checkInWork = work,
        sosEscalator = { reason -> sosReasons.add(reason) },
        nowMs = { now },
        userIdProvider = { "u1" },
        dispatcher = coroutineContext[CoroutineDispatcher]!!,
        externalScope = this
    ).also { engines.add(it) }

    private suspend fun TestScope.startActiveJourney(
        eng: JourneyEngine,
        checkInIntervalMinutes: Int = 1,
        checkInResponseTimeoutSeconds: Int = 30,
        route: List<RoutePoint> = emptyList(),
        etaMs: Long = now + 30 * 60_000L
    ): Journey {
        val res = eng.startJourney(
            destinationName = "Home",
            destinationLat = 37.001,
            destinationLng = -122.0,
            expectedArrivalTime = etaMs,
            checkInIntervalMinutes = checkInIntervalMinutes,
            checkInResponseTimeoutSeconds = checkInResponseTimeoutSeconds,
            routePoints = route
        )
        assertTrue("startJourney failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
        yield()
        return res.getOrThrow()
    }

    @Test
    fun journeyLifecycle_startComplete_updatesStateAndRepo() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng)

        assertEquals(JourneyStatus.ACTIVE, eng.state.value.journey?.statusEnum())
        assertEquals(j.journeyId, runtimeStoreActiveId())

        val complete = eng.completeJourney("Arrived")
        yield()
        assertTrue(complete.isSuccess)
        assertEquals(JourneyStatus.COMPLETED, eng.state.value.journey?.statusEnum())
        assertNull(eng.state.value.pendingCheckIn)
        verify { runtimeStore.clearAll() }
        assertTrue(notifier.cancelAllCalled)
    }

    @Test
    fun duplicateJourneyPrevention_blocksSecondStart() = testEngine {
        val eng = engine()
        startActiveJourney(eng)
        val second = eng.startJourney("Other", 37.002, -122.0, now + 60_000L)
        yield()
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun checkInResponse_acknowledgeUpdatesRepoAndClearsPending() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng, checkInIntervalMinutes = 1, checkInResponseTimeoutSeconds = 30)
        advanceTimeBy(60_001L) // +1ms: advanceTimeBy is exclusive of the target time
        yield()
        val pending = eng.state.value.pendingCheckIn
        assertNotNull(pending)
        assertEquals(CheckInStatus.PENDING, pending!!.statusEnum())

        eng.acknowledgeCheckIn(j.journeyId, pending.checkInId)
        yield()

        assertNull(eng.state.value.pendingCheckIn)
        val saved = repo.getCheckIns(j.journeyId).getOrNull().orEmpty().first { it.checkInId == pending.checkInId }
        assertEquals(CheckInStatus.ACKNOWLEDGED, saved.statusEnum())
        assertEquals("SAFE", saved.response)
        assertTrue(sosReasons.isEmpty())
    }

    @Test
    fun missedCheckIn_escalatesOnceAndClearsPending() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng, checkInIntervalMinutes = 1, checkInResponseTimeoutSeconds = 30)

        advanceTimeBy(60_001L) // +1ms: advanceTimeBy is exclusive of the target time // schedule check-in
        yield()
        val pending = eng.state.value.pendingCheckIn
        assertNotNull(pending)

        advanceTimeBy(30_001L) // timeout (+1ms: target time is exclusive)
        yield()

        val saved = repo.getCheckIns(j.journeyId).getOrNull().orEmpty().first { it.checkInId == pending!!.checkInId }
        assertEquals(CheckInStatus.MISSED, saved.statusEnum())
        assertTrue(saved.escalated)
        assertNull(eng.state.value.pendingCheckIn)
        assertEquals(1, sosReasons.count { it.contains("MISSED_CHECKIN") })
    }

    @Test
    fun delayedArrival_escalatesWhenPastEtaAndNotNearDestination() = testEngine {
        val eng = engine()
        val eta = now + 10_000L
        val j = startActiveJourney(eng, etaMs = eta)
        // Set last location far from destination
        locationFlow.emit(JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = now + 1000))
        yield()

        now = eta + (DEFAULT_ARRIVAL_GRACE_MIN * 60_000L) + 31_000L
        advanceTimeBy(31_000L)
        yield()

        assertTrue(sosReasons.any { it.contains("ARRIVAL_DELAY") })
        assertEquals(j.journeyId, eng.state.value.journey?.journeyId)
    }

    @Test
    fun journeyCancellationCleanup_stopsWorkAndClearsState() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng)
        advanceTimeBy(60_001L) // +1ms: advanceTimeBy is exclusive of the target time
        yield()
        assertNotNull(eng.state.value.pendingCheckIn)

        val cancel = eng.cancelJourney("User cancelled")
        yield()

        assertTrue(cancel.isSuccess)
        assertEquals(JourneyStatus.CANCELLED, eng.state.value.journey?.statusEnum())
        assertNull(eng.state.value.pendingCheckIn)
        assertTrue(work.cancelledJourneys.contains(j.journeyId))
        verify { runtimeStore.clearAll() }
        assertTrue(notifier.cancelAllCalled)
    }

    @Test
    fun duplicateSosPrevention_doesNotEscalateSameMissedCheckInTwice() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng, checkInIntervalMinutes = 1, checkInResponseTimeoutSeconds = 30)
        advanceTimeBy(60_001L) // +1ms: advanceTimeBy is exclusive of the target time
        yield()
        val checkInId = eng.state.value.pendingCheckIn!!.checkInId

        eng.markCheckInMissed(j.journeyId, checkInId)
        eng.markCheckInMissed(j.journeyId, checkInId)
        yield()

        assertEquals(1, sosReasons.count { it.contains("MISSED_CHECKIN") })
    }

    @Test
    fun journeyRestoration_restoresActiveJourneyAndResumesPendingCheckIn() = testEngine {
        val j = Journey(
            journeyId = "j1", userId = "u1", destinationName = "Home",
            destinationLatitude = 37.001, destinationLongitude = -122.0,
            startLatitude = 37.0, startLongitude = -122.0,
            expectedArrivalTime = now + 60 * 60_000L, startedAt = now - 10_000L,
            status = JourneyStatus.ACTIVE.name, isActive = true,
            checkInIntervalMinutes = 5, checkInResponseTimeoutSeconds = 30,
            createdAt = now - 10_000L, updatedAt = now - 10_000L
        )
        repo.createJourney(j)
        val pending = CheckInRecord(
            checkInId = "c1", journeyId = "j1", scheduledAt = now - 5_000L,
            dueAt = now + 5_000L, status = CheckInStatus.PENDING.name
        )
        repo.saveCheckIn("j1", pending)
        every { runtimeStore.getActiveJourneyId() } returns "j1"
        every { runtimeStore.getActiveUserId() } returns "u1"

        val eng = engine()
        eng.restoreActiveJourney()
        yield()

        assertEquals("j1", eng.state.value.journey?.journeyId)
        assertEquals("c1", eng.state.value.pendingCheckIn?.checkInId)

        advanceTimeBy(5_001L)
        yield()
        val saved = repo.getCheckIns("j1").getOrNull().orEmpty().first { it.checkInId == "c1" }
        assertEquals(CheckInStatus.MISSED, saved.statusEnum())
        assertTrue(sosReasons.any { it.contains("MISSED_CHECKIN") })
    }

    @Test
    fun helpRequest_escalatesOnceAndPersistsHelpResponse() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng, checkInIntervalMinutes = 1, checkInResponseTimeoutSeconds = 30)
        advanceTimeBy(60_001L)
        yield()
        val checkIn = eng.state.value.pendingCheckIn!!

        eng.requestHelpOnCheckIn(j.journeyId, checkIn.checkInId)
        eng.requestHelpOnCheckIn(j.journeyId, checkIn.checkInId)
        yield()

        val saved = repo.getCheckIns(j.journeyId).getOrNull()!!.first { it.checkInId == checkIn.checkInId }
        assertEquals(CheckInStatus.HELP_REQUESTED, saved.statusEnum())
        assertEquals("HELP", saved.response)
        assertTrue(saved.escalated)
        assertEquals(1, sosReasons.count { it.contains("USER_REQUESTED_HELP") })
        assertNull(eng.state.value.pendingCheckIn)
    }

    @Test
    fun liveLocation_isPersistedAndCompletionDoesNotEscalateSos() = testEngine {
        val eng = engine()
        val j = startActiveJourney(eng)
        locationFlow.emit(JourneyPoint(37.0001, -122.0, accuracy = 10f, timestamp = now + 1_000))
        yield()
        val savedLocations = repo.locations[j.journeyId].orEmpty()
        assertEquals(2, savedLocations.size) // start fix + live fix
        assertEquals(now + 1_000, savedLocations.last().timestamp)

        assertTrue(eng.completeJourney("Arrived").isSuccess)
        yield()
        assertTrue(sosReasons.isEmpty())
        assertEquals(JourneyStatus.COMPLETED, eng.state.value.journey?.statusEnum())
    }

    @Test
    fun routeDeviation_persistsOneEventAndEscalatesOnce() = testEngine {
        val route = listOf(RoutePoint(37.0, -122.0), RoutePoint(37.001, -122.0))
        val eng = engine()
        val j = startActiveJourney(eng, route = route)

        // Two consecutive off-route fixes are required by the sustain rule.
        locationFlow.emit(JourneyPoint(37.0005, -122.003, accuracy = 5f, timestamp = now + 1_000))
        yield()
        locationFlow.emit(JourneyPoint(37.0006, -122.003, accuracy = 5f, timestamp = now + 2_000))
        yield()

        val events = repo.deviations[j.journeyId].orEmpty()
        assertEquals(1, events.size)
        assertTrue(events.first().distanceFromRouteMeters > 100f)
        assertEquals(1, sosReasons.count { it.contains("ROUTE_DEVIATION") })
    }

    @Test
    fun pendingSync_isExposedAndClearedAfterSuccessfulReplay() = testEngine {
        val pendingRepo = PendingSyncFakeRepository().apply { pending = true }
        repo = pendingRepo
        val eng = engine()
        startActiveJourney(eng)

        assertTrue(eng.state.value.pendingSync)

        pendingRepo.pending = false
        assertTrue(eng.retryPendingSync().isSuccess)
        assertFalse(eng.state.value.pendingSync)
    }

    @Test
    fun clearingCachedJourney_removesLocalStateAndResetsUi() = testEngine {
        repo = PendingSyncFakeRepository()
        val eng = engine()
        startActiveJourney(eng)
        assertNotNull(eng.state.value.journey)
        pendingCleared = false

        eng.clearCachedJourney()

        assertNull(eng.state.value.journey)
        assertFalse(eng.state.value.pendingSync)
        assertTrue(pendingCleared)
    }

    private var pendingCleared = false

    private inner class PendingSyncFakeRepository : FakeJourneyRepository() {
        var pending: Boolean = false
        override suspend fun hasPendingSync(): Boolean = pending
        override suspend fun retryPendingSync(userId: String): Result<Unit> = Result.success(Unit)
        override fun clearLocalState() {
            pendingCleared = true
        }
    }

    private fun runtimeStoreActiveId(): String? {
        val slot = slot<String>()
        verify(atLeast = 1) { runtimeStore.setActiveJourney(capture(slot), any()) }
        return slot.captured
    }

    private class FakeNotifier : JourneyNotifier {
        var cancelAllCalled = false
        val alerts = mutableListOf<Pair<String, String>>()
        override fun showCheckIn(journeyId: String, checkInId: String) {}
        override fun cancelCheckIn() {}
        override fun showAlert(title: String, message: String) { alerts.add(title to message) }
        override fun cancelAll() { cancelAllCalled = true }
    }

    private class FakeCheckInWork : CheckInWorkScheduler {
        val cancelledJourneys = mutableListOf<String>()
        override fun enqueue(journeyId: String, checkInId: String, timeoutMs: Long) {}
        override fun cancel(checkInId: String) {}
        override fun cancelJourney(journeyId: String) { cancelledJourneys.add(journeyId) }
    }
}
