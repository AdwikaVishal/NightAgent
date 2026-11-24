package com.example.nightagent.sos

import android.content.Context
import android.util.Log
import com.example.nightagent.journey.JourneyEngine
import com.example.nightagent.journey.JourneyTrackingService
import com.example.nightagent.journey.model.JourneyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Legacy compatibility facade. New implementation lives in com.example.nightagent.journey.
 * Kept to avoid breaking existing callers while Person 3 migration is completed.
 */
object SafeWalkManager {
    private const val TAG = "SafeWalkManager"

    fun startSafeWalk(context: Context) {
        val appCtx = context.applicationContext
        val engine = JourneyEngine.getInstance(appCtx)
        if (engine.hasActiveJourney()) return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                @OptIn(ExperimentalCoroutinesApi::class)
                val loc = kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                    LocationProvider.getLocation(appCtx) { l -> if (cont.isActive) cont.resume(l) {} }
                }
                if (loc == null) {
                    Log.w(TAG, "Cannot start SafeWalk: location unavailable")
                    return@launch
                }
                val eta = System.currentTimeMillis() + 30 * 60_000L
                val res = engine.startJourney(
                    destinationName = "Safe Walk",
                    destinationLat = loc.latitude,
                    destinationLng = loc.longitude,
                    expectedArrivalTime = eta,
                    checkInIntervalMinutes = 5,
                    checkInResponseTimeoutSeconds = 120,
                    routePoints = emptyList()
                )
                if (res.isSuccess) {
                    JourneyTrackingService.start(appCtx)
                } else {
                    Log.e(TAG, "Failed to start SafeWalk: ${res.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "startSafeWalk failed", e)
            }
        }
    }

    fun stopSafeWalk(context: Context) {
        val appCtx = context.applicationContext
        val engine = JourneyEngine.getInstance(appCtx)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (engine.state.value.journey?.statusEnum() == JourneyStatus.ACTIVE) {
                    engine.cancelJourney("Safe walk stopped")
                    JourneyTrackingService.stop(appCtx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopSafeWalk failed", e)
            }
        }
    }
}
