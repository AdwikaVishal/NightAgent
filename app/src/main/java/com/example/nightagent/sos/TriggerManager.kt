package com.example.nightagent.sos

import android.content.Context
import android.util.Log
import com.example.nightagent.model.SOSState
import com.example.nightagent.model.TriggerType
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified entry point for all emergency triggers (MANUAL, SHAKE, VOICE, POWER_BUTTON, STEALTH, SAFE_WALK).
 * Enforces SafetySettings toggles and prevents duplicate triggers within a dedupe window.
 */
object TriggerManager {

    private const val TAG = "TriggerManager"
    private const val DEDUPE_WINDOW_MS = 2000L

    private val lastTriggerTimes = ConcurrentHashMap<TriggerType, Long>()

    /**
     * Single entry point invoked by UI buttons, sensor detectors, receivers, and background services.
     */
    fun onTrigger(
        context: Context,
        type: TriggerType,
        onSOSComplete: () -> Unit = {}
    ) {
        SafetySettings.init(context)

        // 1. Verify SafetySettings toggles
        if (!isTriggerAllowedBySettings(type)) {
            Log.d(TAG, "Trigger $type ignored: disabled in SafetySettings")
            return
        }

        // 2. Check deduplication window per trigger source
        val now = System.currentTimeMillis()
        val lastTime = lastTriggerTimes[type] ?: 0L
        if (now - lastTime < DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Trigger $type ignored: within deduplication window (${now - lastTime}ms < ${DEDUPE_WINDOW_MS}ms)")
            return
        }

        // 3. Check if an existing session is already in progress
        val currentSession = SOSManager.currentSession()
        if (currentSession != null && currentSession.state in setOf(
                SOSState.INITIATED,
                SOSState.ACTIVE,
                SOSState.ACKNOWLEDGED
            )
        ) {
            Log.d(TAG, "Trigger $type ignored: emergency session ${currentSession.id} is already in state ${currentSession.state}")
            return
        }

        // Record last accepted trigger timestamp
        lastTriggerTimes[type] = now
        Log.d(TAG, "Dispatching trigger: $type")

        // 4. Delegate to SOSManager
        SOSManager.triggerSOS(context, triggerType = type, onSOSComplete = onSOSComplete)
    }

    private fun isTriggerAllowedBySettings(type: TriggerType): Boolean {
        return when (type) {
            TriggerType.SHAKE -> SafetySettings.shakeDetection.value
            TriggerType.VOICE -> SafetySettings.voiceSOS.value
            TriggerType.POWER_BUTTON -> SafetySettings.powerButtonSOS.value
            TriggerType.MANUAL,
            TriggerType.STEALTH,
            TriggerType.SAFE_WALK -> true
        }
    }

    internal fun clearDedupeTimersForTesting() {
        lastTriggerTimes.clear()
    }

    /**
     * Entry point for UI or services to cancel an active emergency session.
     */
    fun cancel(context: Context? = null, reason: String? = null) {
        SOSManager.cancel(context, reason)
    }

    /**
     * Entry point for UI or services to resolve an active emergency session.
     */
    fun resolve(context: Context? = null) {
        SOSManager.resolve(context)
    }

    /**
     * Entry point for UI or services to acknowledge an active emergency session.
     */
    fun acknowledge(context: Context? = null) {
        SOSManager.acknowledge(context)
    }
}
