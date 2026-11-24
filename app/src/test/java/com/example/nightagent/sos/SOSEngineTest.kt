package com.example.nightagent.sos

import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import com.example.nightagent.sos.engine.SOSEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SOSEngineTest {

    private lateinit var engine: SOSEngine

    @Before
    fun setUp() {
        engine = SOSEngine()
    }

    @Test
    fun `test initial state is null`() {
        assertNull(engine.currentSession())
        assertNull(engine.sessionState.value)
    }

    @Test
    fun `test legal transition IDLE to INITIATED`() {
        val idleResult = engine.createIdleSession(userId = "user1", triggerType = TriggerType.MANUAL)
        assertTrue(idleResult.isSuccess)
        assertEquals(SOSState.IDLE, engine.currentSession()?.state)

        val initResult = engine.transitionTo(SOSState.INITIATED)
        assertTrue(initResult.isSuccess)
        assertEquals(SOSState.INITIATED, engine.currentSession()?.state)
    }

    @Test
    fun `test legal transition INITIATED to ACTIVE`() {
        engine.initiateSession(userId = "user1", triggerType = TriggerType.MANUAL)
        val activeResult = engine.activate()
        assertTrue(activeResult.isSuccess)
        assertEquals(SOSState.ACTIVE, engine.currentSession()?.state)
    }

    @Test
    fun `test legal transition INITIATED to CANCELLED`() {
        engine.initiateSession(userId = "user1", triggerType = TriggerType.SHAKE)
        val cancelResult = engine.cancel("User cancelled false alarm")
        assertTrue(cancelResult.isSuccess)
        val session = engine.currentSession()
        assertEquals(SOSState.CANCELLED, session?.state)
        assertEquals("User cancelled false alarm", session?.failureReason)
        assertNotNull(session?.resolvedAt)
    }

    @Test
    fun `test legal transition ACTIVE to ACKNOWLEDGED`() {
        engine.initiateSession(userId = "user1", triggerType = TriggerType.POWER_BUTTON)
        engine.activate()
        val ackResult = engine.acknowledge()
        assertTrue(ackResult.isSuccess)
        assertEquals(SOSState.ACKNOWLEDGED, engine.currentSession()?.state)
    }

    @Test
    fun `test legal transition ACTIVE to RESOLVED`() {
        engine.initiateSession(userId = "user1", triggerType = TriggerType.VOICE)
        engine.activate()
        val resResult = engine.resolve()
        assertTrue(resResult.isSuccess)
        val session = engine.currentSession()
        assertEquals(SOSState.RESOLVED, session?.state)
        assertNotNull(session?.resolvedAt)
    }

    @Test
    fun `test legal transition ACTIVE to CANCELLED`() {
        engine.initiateSession(userId = "user1", triggerType = TriggerType.STEALTH)
        engine.activate()
        val cancelResult = engine.cancel("Resolved by stealth guardian")
        assertTrue(cancelResult.isSuccess)
        assertEquals(SOSState.CANCELLED, engine.currentSession()?.state)
    }

    @Test
    fun `test legal transition ACKNOWLEDGED to RESOLVED`() {
        engine.initiateSession(userId = "user1", triggerType = TriggerType.SAFE_WALK)
        engine.activate()
        engine.acknowledge()
        val resResult = engine.resolve()
        assertTrue(resResult.isSuccess)
        assertEquals(SOSState.RESOLVED, engine.currentSession()?.state)
    }

    @Test
    fun `test illegal transitions return failure and do not crash`() {
        // IDLE cannot jump to ACTIVE, ACKNOWLEDGED, RESOLVED, CANCELLED
        engine.createIdleSession(userId = "user1", triggerType = TriggerType.MANUAL)
        assertFalse(engine.transitionTo(SOSState.ACTIVE).isSuccess)
        assertFalse(engine.transitionTo(SOSState.ACKNOWLEDGED).isSuccess)
        assertFalse(engine.transitionTo(SOSState.RESOLVED).isSuccess)
        assertFalse(engine.transitionTo(SOSState.CANCELLED).isSuccess)
        assertEquals(SOSState.IDLE, engine.currentSession()?.state)

        // INITIATED cannot jump to ACKNOWLEDGED or RESOLVED directly
        engine.initiateSession(userId = "user1", triggerType = TriggerType.MANUAL)
        assertFalse(engine.acknowledge().isSuccess)
        assertFalse(engine.resolve().isSuccess)
        assertEquals(SOSState.INITIATED, engine.currentSession()?.state)

        // ACTIVE cannot go back to INITIATED or IDLE
        engine.activate()
        assertFalse(engine.transitionTo(SOSState.INITIATED).isSuccess)
        assertFalse(engine.transitionTo(SOSState.IDLE).isSuccess)
        assertEquals(SOSState.ACTIVE, engine.currentSession()?.state)

        // ACKNOWLEDGED cannot go to CANCELLED, ACTIVE, or INITIATED
        engine.acknowledge()
        assertFalse(engine.cancel().isSuccess)
        assertFalse(engine.activate().isSuccess)
        assertFalse(engine.transitionTo(SOSState.INITIATED).isSuccess)
        assertEquals(SOSState.ACKNOWLEDGED, engine.currentSession()?.state)

        // RESOLVED is terminal - no transitions allowed
        engine.resolve()
        assertFalse(engine.activate().isSuccess)
        assertFalse(engine.acknowledge().isSuccess)
        assertFalse(engine.cancel().isSuccess)
        assertFalse(engine.transitionTo(SOSState.INITIATED).isSuccess)
        assertEquals(SOSState.RESOLVED, engine.currentSession()?.state)
    }

    @Test
    fun `test duplicate trigger returns the same session during INITIATED, ACTIVE, or ACKNOWLEDGED`() {
        val result1 = engine.initiateSession(userId = "user1", triggerType = TriggerType.MANUAL)
        val session1 = result1.getOrThrow()

        // 2nd trigger while INITIATED
        val result2 = engine.initiateSession(userId = "user1", triggerType = TriggerType.SHAKE)
        val session2 = result2.getOrThrow()
        assertEquals(session1.id, session2.id)
        assertEquals(session1.idempotencyKey, session2.idempotencyKey)
        assertEquals(SOSState.INITIATED, session2.state)

        // 3rd trigger while ACTIVE
        engine.activate()
        val result3 = engine.initiateSession(userId = "user1", triggerType = TriggerType.VOICE)
        val session3 = result3.getOrThrow()
        assertEquals(session1.id, session3.id)
        assertEquals(SOSState.ACTIVE, session3.state)

        // 4th trigger while ACKNOWLEDGED
        engine.acknowledge()
        val result4 = engine.initiateSession(userId = "user1", triggerType = TriggerType.POWER_BUTTON)
        val session4 = result4.getOrThrow()
        assertEquals(session1.id, session4.id)
        assertEquals(SOSState.ACKNOWLEDGED, session4.state)
    }

    @Test
    fun `test cancel before ACTIVE and resolve after ACTIVE`() {
        // Cancel before ACTIVE
        val res1 = engine.initiateSession(userId = "user1", triggerType = TriggerType.MANUAL)
        assertTrue(res1.isSuccess)
        val cancelRes = engine.cancel("Cancelled before active")
        assertTrue(cancelRes.isSuccess)
        assertEquals(SOSState.CANCELLED, engine.currentSession()?.state)

        // New emergency after cancellation
        val res2 = engine.initiateSession(userId = "user1", triggerType = TriggerType.MANUAL)
        assertTrue(res2.isSuccess)
        assertNotEquals(res1.getOrThrow().id, res2.getOrThrow().id)
        assertEquals(SOSState.INITIATED, engine.currentSession()?.state)

        // Activate and resolve
        assertTrue(engine.activate().isSuccess)
        assertEquals(SOSState.ACTIVE, engine.currentSession()?.state)
        val resolveRes = engine.resolve()
        assertTrue(resolveRes.isSuccess)
        assertEquals(SOSState.RESOLVED, engine.currentSession()?.state)
    }

    @Test
    fun `test session restoration after process death`() {
        val session = engine.initiateSession(userId = "user1", triggerType = TriggerType.STEALTH).getOrThrow()
        engine.activate()
        val activeSession = engine.currentSession()!!

        // Simulate new engine instance after process recreation
        val freshEngine = SOSEngine()
        assertNull(freshEngine.currentSession())

        freshEngine.restoreSession(activeSession)
        assertEquals(activeSession.id, freshEngine.currentSession()?.id)
        assertEquals(SOSState.ACTIVE, freshEngine.currentSession()?.state)

        // Can continue lifecycle
        assertTrue(freshEngine.resolve().isSuccess)
        assertEquals(SOSState.RESOLVED, freshEngine.currentSession()?.state)
    }

    @Test
    fun `test trigger after RESOLVED or CANCELLED creates a brand-new session with a new id`() {
        // Trigger 1 -> ACTIVE -> RESOLVED
        val session1 = engine.initiateSession(userId = "user_alpha", triggerType = TriggerType.MANUAL).getOrThrow()
        engine.activate()
        engine.resolve()
        assertEquals(SOSState.RESOLVED, engine.currentSession()?.state)

        // Trigger after RESOLVED must produce a brand-new session with a unique ID
        val session2 = engine.initiateSession(userId = "user_alpha", triggerType = TriggerType.SHAKE).getOrThrow()
        assertNotEquals(session1.id, session2.id)
        assertNotEquals(session1.idempotencyKey, session2.idempotencyKey)
        assertEquals(SOSState.INITIATED, session2.state)
        assertEquals(session2.id, engine.currentSession()?.id)

        // Cancel session 2
        engine.cancel("Cancelled by user")
        assertEquals(SOSState.CANCELLED, engine.currentSession()?.state)

        // Trigger after CANCELLED must produce another brand-new session with a unique ID
        val session3 = engine.initiateSession(userId = "user_alpha", triggerType = TriggerType.VOICE).getOrThrow()
        assertNotEquals(session2.id, session3.id)
        assertNotEquals(session1.id, session3.id)
        assertEquals(SOSState.INITIATED, session3.state)
        assertEquals(session3.id, engine.currentSession()?.id)
    }

    @Test
    fun `test configurable max-duration safety net auto-resolves expired active session`() {
        val startTime = 1_000_000L
        val maxDurationMillis = 30 * 60 * 1000L // 30 minutes configurable safety net

        val session = engine.initiateSession(
            userId = "user1",
            triggerType = TriggerType.MANUAL
        ).getOrThrow().copy(createdAt = startTime)

        // Restore with fixed start timestamp
        engine.restoreSession(session)
        engine.activate()
        assertEquals(SOSState.ACTIVE, engine.currentSession()?.state)

        // Case 1: Check at 29 minutes elapsed -> must NOT expire
        val checkBeforeResult = engine.checkMaxDuration(
            maxDurationMillis = maxDurationMillis,
            currentTimeMillis = startTime + 29 * 60 * 1000L
        )
        assertTrue(checkBeforeResult.isSuccess)
        assertNull(checkBeforeResult.getOrThrow())
        assertEquals(SOSState.ACTIVE, engine.currentSession()?.state)

        // Case 2: Check at 30 minutes elapsed -> must auto-resolve
        val checkAfterResult = engine.checkMaxDuration(
            maxDurationMillis = maxDurationMillis,
            currentTimeMillis = startTime + 30 * 60 * 1000L
        )
        assertTrue(checkAfterResult.isSuccess)
        val resolvedSession = checkAfterResult.getOrThrow()
        assertNotNull(resolvedSession)
        assertEquals(SOSState.RESOLVED, resolvedSession?.state)
        assertTrue(resolvedSession?.failureReason!!.contains("Auto-resolved"))
        assertEquals(SOSState.RESOLVED, engine.currentSession()?.state)
    }

    @Test
    fun `test updateUserId changes userId without changing the state`() {
        engine.initiateSession(userId = "anonymous_user", triggerType = TriggerType.SHAKE)
        assertEquals(SOSState.INITIATED, engine.currentSession()?.state)
        assertEquals("anonymous_user", engine.currentSession()?.userId)

        val updatedInitiated = engine.updateUserId("real_uid_123")
        assertNotNull(updatedInitiated)
        assertEquals("real_uid_123", updatedInitiated?.userId)
        assertEquals(SOSState.INITIATED, updatedInitiated?.state)
        assertEquals(SOSState.INITIATED, engine.currentSession()?.state)

        engine.activate()
        assertEquals(SOSState.ACTIVE, engine.currentSession()?.state)

        val updatedActive = engine.updateUserId("real_uid_456")
        assertNotNull(updatedActive)
        assertEquals("real_uid_456", updatedActive?.userId)
        assertEquals(SOSState.ACTIVE, updatedActive?.state)
    }

    @Test
    fun `test auto-resolve path cancels an INITIATED session instead of failing`() {
        val startTime = 1_000_000L
        val maxDurationMillis = 10 * 60 * 1000L

        val session = engine.initiateSession(
            userId = "user1",
            triggerType = TriggerType.MANUAL
        ).getOrThrow().copy(createdAt = startTime)

        engine.restoreSession(session)
        assertEquals(SOSState.INITIATED, engine.currentSession()?.state)

        // Check after expiration
        val checkResult = engine.checkMaxDuration(
            maxDurationMillis = maxDurationMillis,
            currentTimeMillis = startTime + 15 * 60 * 1000L
        )
        assertTrue(checkResult.isSuccess)
        val cancelledSession = checkResult.getOrThrow()
        assertNotNull(cancelledSession)
        assertEquals(SOSState.CANCELLED, cancelledSession?.state)
        assertEquals(SOSState.CANCELLED, engine.currentSession()?.state)
        assertTrue(cancelledSession?.failureReason?.contains("Auto-resolved") == true)
    }
}
