package com.example.nightagent.sos

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.nightagent.model.EmergencySession
import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TriggerManagerTest {

    private lateinit var mockContext: Context
    private val prefsStore = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        prefsStore.clear()
        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper

        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        mockkStatic(Toast::class)
        val mockToast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any<Context>(), any<CharSequence>(), any<Int>()) } returns mockToast
        every { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) } returns mockToast

        mockkObject(LocationProvider)
        every { LocationProvider.getLocation(any(), any()) } answers {
            val callback = secondArg<(android.location.Location?) -> Unit>()
            callback(null)
        }

        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { mockEditor.putString(any(), any()) } answers {
            prefsStore[firstArg()] = secondArg()
            mockEditor
        }
        every { mockEditor.remove(any()) } answers {
            prefsStore.remove(firstArg())
            mockEditor
        }

        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { mockPrefs.getString(any(), any()) } answers {
            prefsStore[firstArg()] ?: secondArg()
        }
        every { mockPrefs.edit() } returns mockEditor

        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs

        SOSManager.resetForTesting()
        SafetySettings.resetForTesting()
        TriggerManager.clearDedupeTimersForTesting()
        SOSManager.engine.reset()
        SOSManager.repository.clearLocalSession(mockContext)
    }

    @After
    fun tearDown() {
        SOSManager.resetForTesting()
        TriggerManager.clearDedupeTimersForTesting()
        SOSManager.engine.reset()
        SOSManager.repository.clearLocalSession(mockContext)
        unmockkAll()
    }

    @Test
    fun `test trigger ignored when SafetySettings toggle is disabled`() {
        SafetySettings.resetForTesting(shake = false, voice = false, power = false)

        // Shake trigger
        TriggerManager.onTrigger(mockContext, TriggerType.SHAKE)
        assertNull(SOSManager.currentSession())

        // Voice trigger
        TriggerManager.onTrigger(mockContext, TriggerType.VOICE)
        assertNull(SOSManager.currentSession())

        // Power button trigger
        TriggerManager.onTrigger(mockContext, TriggerType.POWER_BUTTON)
        assertNull(SOSManager.currentSession())
    }

    @Test
    fun `test trigger accepted when SafetySettings toggle is enabled`() {
        SafetySettings.resetForTesting(shake = true)

        TriggerManager.onTrigger(mockContext, TriggerType.SHAKE)
        val session = SOSManager.currentSession()
        assertNotNull(session)
        assertEquals(TriggerType.SHAKE, session?.triggerType)
    }

    @Test
    fun `test manual, stealth, and safe walk triggers are always enabled`() {
        SafetySettings.resetForTesting(shake = false, voice = false, power = false)

        // Manual
        TriggerManager.onTrigger(mockContext, TriggerType.MANUAL)
        assertEquals(TriggerType.MANUAL, SOSManager.currentSession()?.triggerType)

        // Stealth
        SOSManager.engine.reset()
        TriggerManager.clearDedupeTimersForTesting()
        TriggerManager.onTrigger(mockContext, TriggerType.STEALTH)
        assertEquals(TriggerType.STEALTH, SOSManager.currentSession()?.triggerType)

        // Safe Walk
        SOSManager.engine.reset()
        TriggerManager.clearDedupeTimersForTesting()
        TriggerManager.onTrigger(mockContext, TriggerType.SAFE_WALK)
        assertEquals(TriggerType.SAFE_WALK, SOSManager.currentSession()?.triggerType)
    }

    @Test
    fun `test rapid repeat triggers within dedupe window are ignored`() {
        SafetySettings.resetForTesting(shake = true)

        TriggerManager.onTrigger(mockContext, TriggerType.SHAKE)
        val session1 = SOSManager.currentSession()
        assertNotNull(session1)

        // Reset engine to test TriggerManager-level deduplication window
        SOSManager.engine.reset()

        // Immediate 2nd trigger of same type
        TriggerManager.onTrigger(mockContext, TriggerType.SHAKE)
        // Should have been dropped by deduplication window
        assertNull(SOSManager.currentSession())
    }

    @Test
    fun `test onSOSComplete is invoked when session reaches RESOLVED`() {
        SafetySettings.resetForTesting()
        var completed = false

        TriggerManager.onTrigger(mockContext, TriggerType.MANUAL) {
            completed = true
        }

        assertNotNull(SOSManager.currentSession())
        assertFalse("Callback must not be invoked prematurely while session is active", completed)

        // Resolve session
        TriggerManager.resolve(mockContext)

        assertEquals(SOSState.RESOLVED, SOSManager.currentSession()?.state)
        assertTrue("Callback must be invoked upon RESOLVED so services like EvidenceCaptureService stop", completed)
    }

    @Test
    fun `test onSOSComplete is invoked when session reaches CANCELLED`() {
        SafetySettings.resetForTesting()
        var completed = false

        TriggerManager.onTrigger(mockContext, TriggerType.MANUAL) {
            completed = true
        }

        assertNotNull(SOSManager.currentSession())
        assertFalse("Callback must not be invoked prematurely while session is active", completed)

        // Cancel session
        TriggerManager.cancel(mockContext, "False alarm")

        assertEquals(SOSState.CANCELLED, SOSManager.currentSession()?.state)
        assertTrue("Callback must be invoked upon CANCELLED", completed)
    }

    @Test
    fun `test cancel from notification action and from UI path`() {
        SafetySettings.resetForTesting()
        var completed = false

        // 1. Notification action path
        TriggerManager.onTrigger(mockContext, TriggerType.MANUAL) {
            completed = true
        }
        assertNotNull(SOSManager.currentSession())
        assertEquals(SOSState.ACTIVE, SOSManager.currentSession()?.state)

        val receiver = SOSActionReceiver()
        val cancelIntent = mockk<Intent>()
        every { cancelIntent.action } returns SOSActionReceiver.ACTION_CANCEL_SOS
        receiver.onReceive(mockContext, cancelIntent)

        assertEquals(SOSState.CANCELLED, SOSManager.currentSession()?.state)
        assertEquals("Cancelled by user", SOSManager.currentSession()?.failureReason)
        assertTrue(completed)

        // 2. UI path (TriggerManager.cancel)
        completed = false
        SOSManager.engine.reset()
        TriggerManager.clearDedupeTimersForTesting()
        TriggerManager.onTrigger(mockContext, TriggerType.MANUAL) {
            completed = true
        }
        assertEquals(SOSState.ACTIVE, SOSManager.currentSession()?.state)

        TriggerManager.cancel(mockContext, "Cancelled by user")
        assertEquals(SOSState.CANCELLED, SOSManager.currentSession()?.state)
        assertEquals("Cancelled by user", SOSManager.currentSession()?.failureReason)
        assertTrue(completed)
    }

    @Test
    fun `test restoreUnfinishedSession rescheduling or expiring auto-resolve`() {
        val now = System.currentTimeMillis()

        // Case 1: Session not yet expired -> should restore session and keep active
        val activeSession = EmergencySession(
            id = "sess_not_expired",
            userId = "user_abc",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.MANUAL,
            createdAt = now - (10 * 60 * 1000L), // 10 minutes ago (within 60m max duration)
            idempotencyKey = "idem_active"
        )
        SOSManager.repository.saveLocalSession(mockContext, activeSession)
        SOSManager.maxDurationMinutes = 60L

        SOSManager.restoreUnfinishedSession(mockContext)
        assertEquals("sess_not_expired", SOSManager.currentSession()?.id)
        assertEquals(SOSState.ACTIVE, SOSManager.currentSession()?.state)

        // Case 2: Session already expired (> 60m ago) -> should expire immediately to RESOLVED
        SOSManager.resetForTesting()
        SOSManager.engine.reset()
        SOSManager.repository.clearLocalSession(mockContext)

        val expiredSession = EmergencySession(
            id = "sess_already_expired",
            userId = "user_abc",
            state = SOSState.ACTIVE,
            triggerType = TriggerType.MANUAL,
            createdAt = now - (70 * 60 * 1000L), // 70 minutes ago
            idempotencyKey = "idem_expired"
        )
        SOSManager.repository.saveLocalSession(mockContext, expiredSession)

        SOSManager.restoreUnfinishedSession(mockContext)
        assertEquals("sess_already_expired", SOSManager.currentSession()?.id)
        assertEquals(SOSState.RESOLVED, SOSManager.currentSession()?.state)
        assertTrue(SOSManager.currentSession()?.failureReason?.contains("Auto-resolved") == true)
    }

    @Test
    fun `test onAuthenticated moves engine session from anonymous_user to real uid and is saved`() {
        SOSManager.resetForTesting()
        SOSManager.engine.reset()
        SOSManager.repository.clearLocalSession(mockContext)

        // Initiate session with anonymous_user
        SOSManager.engine.initiateSession(userId = "anonymous_user", triggerType = TriggerType.MANUAL)
        SOSManager.engine.activate()
        assertEquals("anonymous_user", SOSManager.currentSession()?.userId)
        assertEquals(SOSState.ACTIVE, SOSManager.currentSession()?.state)

        // Authenticate with real uid
        SOSManager.onAuthenticated(mockContext, "real_firebase_user_777")

        // Poll until repository reflects new uid (up to ~2 seconds)
        var attempts = 0
        var savedUserId: String?
        do {
            savedUserId = SOSManager.repository.getLocalSession(mockContext)?.userId
            if (savedUserId == "real_firebase_user_777") break
            Thread.sleep(40)
            attempts++
        } while (attempts < 50)
        assertEquals("real_firebase_user_777", savedUserId)
        assertEquals("real_firebase_user_777", SOSManager.currentSession()?.userId)
        assertEquals(SOSState.ACTIVE, SOSManager.currentSession()?.state)
        val saved = SOSManager.repository.getLocalSession(mockContext)
        assertEquals("real_firebase_user_777", saved?.userId)
    }

    @Test
    fun `test restored INITIATED session becomes ACTIVE`() {
        SOSManager.resetForTesting()
        SOSManager.engine.reset()
        SOSManager.repository.clearLocalSession(mockContext)

        val initiatedSession = EmergencySession(
            id = "sess_restored_init",
            userId = "user_restored",
            state = SOSState.INITIATED,
            triggerType = TriggerType.POWER_BUTTON,
            createdAt = System.currentTimeMillis() - 1000L,
            idempotencyKey = "idem_init"
        )
        SOSManager.repository.saveLocalSession(mockContext, initiatedSession)

        SOSManager.restoreUnfinishedSession(mockContext)

        val current = SOSManager.currentSession()
        assertNotNull(current)
        assertEquals("sess_restored_init", current?.id)
        assertEquals(SOSState.ACTIVE, current?.state)
    }

    @Test
    fun `test an unsynced RESOLVED or CANCELLED session is re-saved on app start`() {
        SOSManager.engine.reset()
        // Reset SOSManager state for isolated testing
        SOSManager.resetForTesting()
        // Cancel any background retry coroutines
        SOSManager.repository.cancelRetries()

        val failedSession = EmergencySession(
            id = "sess_failed_finish",
            userId = "user_real",
            state = SOSState.RESOLVED,
            triggerType = TriggerType.MANUAL,
            resolvedAt = System.currentTimeMillis() - 5000L,
            failureReason = "Timeout writing final doc",
            idempotencyKey = "idem_fail"
        )
        SOSManager.repository.saveLocalSession(mockContext, failedSession)

        // Run resync on app start
        SOSManager.resyncPendingSession(mockContext)

        // The session is not loaded into engine (remains finished), but is re-saved to trigger retry
        assertNull(SOSManager.currentSession())
        val local = SOSManager.repository.getLocalSession(mockContext)
        assertEquals("sess_failed_finish", local?.id)
    }
}
