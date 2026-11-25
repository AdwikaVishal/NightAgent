package com.example.nightagent.journey

import android.content.Context
import android.content.SharedPreferences
import com.example.nightagent.journey.model.Journey
import com.example.nightagent.journey.model.JourneyStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-local recovery cache: Firestore writes may be queued while offline,
 * but the active journey must still be readable immediately after a restart.
 */
class JourneyLocalStoreTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val stored = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        stored.clear()

        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putString(any(), anyNullable()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            stored.remove(firstArg())
            editor
        }
        every { editor.apply() } just runs
        every { prefs.getString(any(), anyNullable()) } answers { stored[firstArg()] ?: secondArg() }
    }

    private fun journey(userId: String = "u1") = Journey(
        journeyId = "j-offline",
        userId = userId,
        destinationName = "Home",
        status = JourneyStatus.ACTIVE.name,
        isActive = true,
        createdAt = 1_000L,
        updatedAt = 2_000L
    )

    @Test
    fun activeJourney_survivesOfflineRestart() {
        val store = JourneyLocalStore(context)
        val journey = journey()

        store.save(journey)

        val restartedStore = JourneyLocalStore(context)
        assertEquals(journey, restartedStore.getActiveJourney("u1"))
        assertEquals(journey, restartedStore.getJourney("j-offline"))
    }

    @Test
    fun cachedJourney_isNotReturnedForAnotherOwner() {
        val store = JourneyLocalStore(context)
        store.save(journey())

        assertNull(store.getActiveJourney("other-user"))
    }

    @Test
    fun clear_removesRecoverableJourney() {
        val store = JourneyLocalStore(context)
        store.save(journey())

        store.clear()

        assertNull(store.getActiveJourney("u1"))
        assertNull(store.getJourney("j-offline"))
    }

    @Test
    fun pendingQueues_surviveRestartAndAreDeduplicatedPerJourney() {
        val store = JourneyLocalStore(context)
        val point = com.example.nightagent.journey.model.JourneyPoint(12.5, 77.1, timestamp = 1_000L)
        store.enqueueUpdate(JourneyLocalStore.PendingUpdate("j-offline", "u1", mapOf("status" to "ACTIVE")))
        store.enqueueUpdate(JourneyLocalStore.PendingUpdate("j-offline", "u1", mapOf("status" to "COMPLETED")))
        store.enqueueLocation("j-offline", "u1", point)

        val restarted = JourneyLocalStore(context)
        assertEquals(1, restarted.pendingUpdates().size)
        assertEquals("COMPLETED", restarted.pendingUpdates().first().updates["status"])
        assertEquals(1, restarted.pendingLocations().size)

        restarted.clearPendingUpdates("j-offline")
        restarted.clearPendingLocations("j-offline")
        assertTrue(restarted.pendingUpdates().isEmpty())
        assertTrue(restarted.pendingLocations().isEmpty())
    }

    @Test
    fun pendingCreateOwner_isOwnerScopedAndClearedOnRecovery() {
        val store = JourneyLocalStore(context)
        store.markPendingCreate("u1")

        assertEquals("u1", JourneyLocalStore(context).pendingCreateOwner())
        store.clear()
        assertNull(JourneyLocalStore(context).pendingCreateOwner())
    }
}
