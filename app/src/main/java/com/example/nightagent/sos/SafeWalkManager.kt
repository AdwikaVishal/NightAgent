package com.example.nightagent.sos

import android.content.Context
import android.util.Log
import com.example.nightagent.journey.JourneyEngine
import com.example.nightagent.journey.JourneyTrackingService
import com.example.nightagent.journey.model.Journey
import com.example.nightagent.journey.model.JourneyStatus
import com.example.nightagent.journey.model.RoutePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Legacy compatibility facade. The only Safe Journey lifecycle is owned by
 * JourneyEngine; this object contains no GPS loop, scheduler, or SOS lifecycle.
 * Callers should supply a real destination so arrival and route logic remain
 * meaningful.
 */
object SafeWalkManager {
    private const val TAG = "SafeWalkManager"

    fun startSafeWalk(
        context: Context,
        destinationName: String,
        destinationLat: Double,
        destinationLng: Double,
        expectedArrivalTime: Long,
        routePoints: List<RoutePoint> = emptyList()
    ) {
        val appCtx = context.applicationContext
        val engine = JourneyEngine.getInstance(appCtx)
        if (engine.hasActiveJourney()) {
            Log.w(TAG, "Safe walk ignored: another journey is already active")
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            val result = engine.startJourney(
                destinationName = destinationName,
                destinationLat = destinationLat,
                destinationLng = destinationLng,
                expectedArrivalTime = expectedArrivalTime,
                routePoints = routePoints
            )
            if (result.isSuccess) {
                JourneyTrackingService.start(appCtx)
            } else {
                Log.e(TAG, "Failed to start SafeWalk: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Retained for source compatibility only: without a destination the engine
     * cannot do arrival or deviation detection, so delegating would create an
     * invalid journey. Use the overload above.
     */
    fun startSafeWalk(context: Context) {
        Log.w(TAG, "startSafeWalk requires an explicit destination; no journey started")
    }

    fun stopSafeWalk(context: Context) {
        val appCtx = context.applicationContext
        val engine = JourneyEngine.getInstance(appCtx)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (engine.state.value.journey?.statusEnum() == JourneyStatus.ACTIVE) {
                    // Engine also stops the foreground service and watchdog work.
                    engine.cancelJourney("Safe walk stopped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopSafeWalk failed", e)
            }
        }
    }
}
