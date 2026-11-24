package com.example.nightagent.journey

import android.content.Context
import com.example.nightagent.journey.model.JourneyConfig
import com.example.nightagent.journey.model.JourneyPoint
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class LocationTrackerFilterTest {

    private val context: Context = mockk(relaxed = true)
    // Fixed clock keeps the test timestamps deterministic; production uses System.currentTimeMillis().
    private val tracker = LocationTracker(context, nowMs = { 2_000L })
    private val config = JourneyConfig(minLocationAccuracyMeters = 50f, maxImplausibleSpeedMps = 20f)

    @Test
    fun acceptsValidFirstPoint() {
        val p = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 1000L)
        assertTrue(tracker.shouldAccept(p, config))
    }

    @Test
    fun rejectsInaccuratePoint() {
        val p = JourneyPoint(37.0, -122.0, accuracy = 100f, timestamp = 1000L)
        assertFalse(tracker.shouldAccept(p, config))
    }

    @Test
    fun rejectsStaleTimestamp() {
        val p1 = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 2000L)
        val p2 = JourneyPoint(37.0001, -122.0, accuracy = 10f, timestamp = 1000L)
        assertTrue(tracker.shouldAccept(p1, config))
        assertFalse(tracker.shouldAccept(p2, config))
    }

    @Test
    fun rejectsImplausibleJump() {
        val p1 = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 1000L)
        // ~1km in 1s => 1000 m/s, implausible
        val p2 = JourneyPoint(37.009, -122.0, accuracy = 20f, timestamp = 2000L)
        assertTrue(tracker.shouldAccept(p1, config))
        assertFalse(tracker.shouldAccept(p2, config))
    }

    @Test
    fun rejectsDuplicateStationaryPoint() {
        val p1 = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 1000L)
        val p2 = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 2000L)
        assertTrue(tracker.shouldAccept(p1, config))
        assertFalse(tracker.shouldAccept(p2, config))
    }

    @Test
    fun rejectsLocationOlderThanMaxAge() {
        val staleTracker = LocationTracker(context, nowMs = { 2_000_000L })
        val stale = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 1_000L)
        assertFalse(staleTracker.shouldAccept(stale, config))
    }

    @Test
    fun rejectsFutureTimestamp() {
        val future = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 2_000L + 120_000L)
        assertFalse(tracker.shouldAccept(future, config))
    }

    @Test
    fun rejectsImplausibleJumpEvenWhenReportedAccuracyIsHigh() {
        val p1 = JourneyPoint(37.0, -122.0, accuracy = 5f, timestamp = 1000L)
        // ~1km in 1s with a confident 5m accuracy fix must still be rejected.
        val p2 = JourneyPoint(37.009, -122.0, accuracy = 5f, timestamp = 2000L)
        assertTrue(tracker.shouldAccept(p1, config))
        assertFalse(tracker.shouldAccept(p2, config))
    }

    @Test
    fun acceptsNormalWalkingMovement() {
        val p1 = JourneyPoint(37.0, -122.0, accuracy = 10f, timestamp = 1000L)
        val p2 = JourneyPoint(37.0001, -122.0, accuracy = 10f, timestamp = 2000L)
        assertTrue(tracker.shouldAccept(p1, config))
        assertTrue(tracker.shouldAccept(p2, config))
    }
}
