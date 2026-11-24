package com.example.nightagent.sos

import android.content.Context
import android.content.SharedPreferences
import com.example.nightagent.model.EmergencySession
import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import com.example.nightagent.repository.EmergencySessionRepository
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencySessionRepositoryTest {

    @After
    fun tearDown() {
        // Cancel any background retry coroutines for all repositories created in tests
        testRepos.forEach { it.cancelRetries() }
        testRepos.clear()
    }
    private val testRepos = mutableListOf<EmergencySessionRepository>()

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val prefsStore = mutableMapOf<String, String>()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun runRepoTest(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            block()
        } finally {
            testRepos.forEach { it.cancelRetries() }
            testRepos.clear()
        }
    }

    private fun newRepo(firestore: FirebaseFirestore?) =
        EmergencySessionRepository(firestore = firestore, dispatcher = testDispatcher).also { testRepos.add(it) }

    @Before
    fun setUp() {
        prefsStore.clear()

        mockEditor = mockk(relaxed = true)
        every { mockEditor.putString(any(), any()) } answers {
            prefsStore[firstArg()] = secondArg()
            mockEditor
        }
        every { mockEditor.remove(any()) } answers {
            prefsStore.remove(firstArg())
            mockEditor
        }

        mockPrefs = mockk(relaxed = true)
        every { mockPrefs.getString(any(), any()) } answers {
            prefsStore[firstArg()] ?: secondArg()
        }
        every { mockPrefs.edit() } returns mockEditor

        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
    }

    @Test
    fun `test unauthenticated user does not fail silently and preserves session locally`() = runRepoTest {
        val repo = newRepo(null)

        val session = EmergencySession(
            id = "sess_unauth",
            userId = "anonymous_user",
            state = SOSState.INITIATED,
            triggerType = TriggerType.MANUAL,
            idempotencyKey = "idem_1"
        )

        val resultSession = repo.saveSession(mockContext, session)

        // Session must be kept, not dropped
        assertEquals("sess_unauth", resultSession.id)
        assertNotNull(resultSession.failureReason)
        assertTrue(resultSession.failureReason!!.contains("not authenticated"))

        // Persisted in local storage
        val local = repo.getLocalSession(mockContext)
        assertNotNull(local)
        assertEquals("sess_unauth", local?.id)
        assertEquals(resultSession.failureReason, local?.failureReason)
    }

    @Test
    fun `test firestore write failure preserves session locally, surfaces failureReason and schedules retry`() = runRepoTest {
        val mockFirestore = mockk<FirebaseFirestore>()
        val mockCollection = mockk<CollectionReference>()
        val mockDoc = mockk<DocumentReference>()

        every { mockFirestore.collection(any()) } returns mockCollection
        every { mockCollection.document(any()) } returns mockDoc
        every { mockDoc.collection(any()) } returns mockCollection
        // Simulate network failure
        every { mockDoc.set(any(), any()) } throws RuntimeException("Network unreachable")

        val repo = newRepo(mockFirestore)

        val session = EmergencySession(
            id = "sess_offline",
            userId = "valid_user_123",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.POWER_BUTTON,
            startLat = 37.7749,
            startLng = -122.4194,
            idempotencyKey = "idem_offline"
        )

        val resultSession = repo.saveSession(mockContext, session)

        // Verifies session is not dropped, kept in memory and storage with failure reason
        assertEquals("sess_offline", resultSession.id)
        assertNotNull(resultSession.failureReason)
        assertTrue(resultSession.failureReason!!.contains("Network unreachable"))

        val local = repo.getLocalSession(mockContext)
        assertNotNull(local)
        assertEquals("sess_offline", local?.id)
        assertEquals("Network unreachable", local?.failureReason)
    }

    @Test
    fun `test reload unfinished session after process death`() {
        val repo = newRepo(null)

        // Case 1: Unfinished session in ACTIVE state
        val activeSession = EmergencySession(
            id = "sess_active_survivor",
            userId = "user_survivor",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.STEALTH,
            idempotencyKey = "idem_survivor"
        )
        repo.saveLocalSession(mockContext, activeSession)

        val recoveredActive = repo.getUnfinishedSession(mockContext)
        assertNotNull(recoveredActive)
        assertEquals("sess_active_survivor", recoveredActive?.id)
        assertEquals(SOSState.ACTIVE, recoveredActive?.state)

        // Case 2: Resolved session must NOT be reloaded
        val resolvedSession = activeSession.copy(state = SOSState.RESOLVED, resolvedAt = System.currentTimeMillis())
        repo.saveLocalSession(mockContext, resolvedSession)

        val recoveredResolved = repo.getUnfinishedSession(mockContext)
        assertNull(recoveredResolved)

        // Case 3: Cancelled session must NOT be reloaded
        val cancelledSession = activeSession.copy(state = SOSState.CANCELLED, resolvedAt = System.currentTimeMillis())
        repo.saveLocalSession(mockContext, cancelledSession)

        val recoveredCancelled = repo.getUnfinishedSession(mockContext)
        assertNull(recoveredCancelled)
    }

    @Test
    fun `test retry still happening for RESOLVED or CANCELLED sessions when not yet synced`() = runRepoTest {
        val mockFirestore = mockk<FirebaseFirestore>()
        val mockCollection = mockk<CollectionReference>()
        val mockDoc = mockk<DocumentReference>()

        every { mockFirestore.collection(any()) } returns mockCollection
        every { mockCollection.document(any()) } returns mockDoc
        every { mockDoc.collection(any()) } returns mockCollection
        // Simulate network failure
        every { mockDoc.set(any(), any()) } throws RuntimeException("Firestore unavailable")

        val repo = newRepo(mockFirestore)

        val resolvedSession = EmergencySession(
            id = "sess_resolved_fail",
            userId = "user_123",
            state = SOSState.RESOLVED,
            triggerType = TriggerType.MANUAL,
            resolvedAt = System.currentTimeMillis(),
            idempotencyKey = "idem_res"
        )

        val saved = repo.saveSession(mockContext, resolvedSession)
        assertEquals("sess_resolved_fail", saved.id)
        assertNotNull(saved.failureReason)
        assertEquals("Firestore unavailable", saved.failureReason)

        // Session is saved in local storage with failureReason set, ready for retry
        val local = repo.getLocalSession(mockContext)
        assertNotNull(local)
        assertEquals(SOSState.RESOLVED, local?.state)
        assertEquals("Firestore unavailable", local?.failureReason)
    }

    @Test
    fun `test anonymous_user session being re-assigned after auth`() = runRepoTest {
        val repo = newRepo(null)

        val anonSession = EmergencySession(
            id = "sess_anon_to_real",
            userId = "anonymous_user",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.SHAKE,
            idempotencyKey = "idem_anon"
        )
        repo.saveSession(mockContext, anonSession)

        // Reassign to real uid
        val reassigned = repo.reassignAnonymousSession(mockContext, "real_firebase_uid_999")
        assertNotNull(reassigned)
        assertEquals("sess_anon_to_real", reassigned?.id)
        assertEquals("real_firebase_uid_999", reassigned?.userId)

        val local = repo.getLocalSession(mockContext)
        assertEquals("real_firebase_uid_999", local?.userId)
    }

    @Test
    fun `test scheduleRetry keeps retrying past 5 attempts until sync succeeds`() = runRepoTest {
        val mockFirestore = mockk<FirebaseFirestore>()
        val mockCollection = mockk<CollectionReference>()
        val mockDoc = mockk<DocumentReference>()

        every { mockFirestore.collection(any()) } returns mockCollection
        every { mockCollection.document(any()) } returns mockDoc
        every { mockDoc.collection(any()) } returns mockCollection

        var attempts = 0
        every { mockDoc.set(any(), any()) } answers {
            attempts++
            if (attempts <= 7) {
                mockk<com.google.android.gms.tasks.Task<Void>> {
                    every { isComplete } returns true
                    every { isSuccessful } returns false
                    every { exception } returns RuntimeException("Network error on attempt $attempts")
                }
                throw RuntimeException("Network error on attempt $attempts")
            } else {
                val successTask = mockk<com.google.android.gms.tasks.Task<Void>>()
                every { successTask.isComplete } returns true
                every { successTask.isSuccessful } returns true
                every { successTask.isCanceled } returns false
                every { successTask.exception } returns null
                every { successTask.result } returns null
                successTask
            }
        }

        val repo = newRepo(mockFirestore)
        val session = EmergencySession(
            id = "sess_retry_many",
            userId = "user_patient",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.MANUAL,
            idempotencyKey = "idem_many"
        )

        repo.saveSession(mockContext, session)
        // Advance virtual time through multiple exponential delays (1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s...)
        testScheduler.advanceTimeBy(150_000L)
        testScheduler.runCurrent()

        assertTrue("Retry must attempt past 5 times (attempt count: $attempts)", attempts > 5)
        val synced = repo.getLocalSession(mockContext)
        assertNotNull(synced)
        assertNull(synced?.failureReason)
    }

    @Test
    fun `test getUnsyncedSession returns local session with failureReason regardless of state`() {
        val repo = newRepo(null)

        // Case 1: Active session without failure -> null
        val okSession = EmergencySession(
            id = "sess_ok",
            userId = "user_1",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.MANUAL,
            idempotencyKey = "idem_ok"
        )
        repo.saveLocalSession(mockContext, okSession)
        assertNull(repo.getUnsyncedSession(mockContext))

        // Case 2: Resolved session with failure -> returned
        val failedResolved = okSession.copy(
            id = "sess_failed_res",
            state = SOSState.RESOLVED,
            failureReason = "Timeout writing to Firestore"
        )
        repo.saveLocalSession(mockContext, failedResolved)
        val unsyncedResolved = repo.getUnsyncedSession(mockContext)
        assertNotNull(unsyncedResolved)
        assertEquals("sess_failed_res", unsyncedResolved?.id)

        // Case 3: Cancelled session with failure -> returned
        val failedCancelled = okSession.copy(
            id = "sess_failed_can",
            state = SOSState.CANCELLED,
            failureReason = "Network down"
        )
        repo.saveLocalSession(mockContext, failedCancelled)
        val unsyncedCancelled = repo.getUnsyncedSession(mockContext)
        assertNotNull(unsyncedCancelled)
        assertEquals("sess_failed_can", unsyncedCancelled?.id)

        // Case 4: Anonymous user with failure -> null (not synced until real uid is provided)
        val anonFailed = failedResolved.copy(userId = "anonymous_user")
        repo.saveLocalSession(mockContext, anonFailed)
        assertNull(repo.getUnsyncedSession(mockContext))
    }

    @Test
    fun `test clear local session removes session from storage`() {
        val repo = newRepo(null)
        val session = EmergencySession(
            id = "sess_to_clear",
            userId = "user_clear",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.SAFE_WALK,
            idempotencyKey = "idem_clear"
        )
        repo.saveLocalSession(mockContext, session)
        assertNotNull(repo.getLocalSession(mockContext))

        repo.clearLocalSession(mockContext)
        assertNull(repo.getLocalSession(mockContext))
        assertNull(repo.getUnfinishedSession(mockContext))
    }
}
