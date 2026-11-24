package com.example.nightagent.journey

import com.example.nightagent.journey.model.JourneyPoint
import com.example.nightagent.journey.model.RoutePoint
import org.junit.Assert.*
import org.junit.Test

class RouteDeviationDetectorTest {

    private val route = listOf(
        RoutePoint(37.0, -122.0),
        RoutePoint(37.001, -122.0),
        RoutePoint(37.002, -122.0)
    )

    @Test
    fun onRoute_returnsNoDeviation() {
        val detector = RouteDeviationDetector(thresholdMeters = 50f, sustainCount = 1)
        val point = JourneyPoint(latitude = 37.0005, longitude = -122.0, accuracy = 5f, timestamp = 1000)
        val res = detector.evaluate(point, route)
        assertFalse(res.isDeviated)
        assertTrue(res.distanceMeters < 20f)
    }

    @Test
    fun farFromRoute_sustainedDeviationDetected() {
        val detector = RouteDeviationDetector(thresholdMeters = 50f, sustainCount = 2)
        val p1 = JourneyPoint(latitude = 37.0005, longitude = -122.002, accuracy = 5f, timestamp = 1000)
        val p2 = JourneyPoint(latitude = 37.0006, longitude = -122.002, accuracy = 5f, timestamp = 2000)
        val r1 = detector.evaluate(p1, route)
        assertFalse(r1.isDeviated) // first deviation not sustained
        val r2 = detector.evaluate(p2, route)
        assertTrue(r2.isDeviated)
        assertTrue(r2.distanceMeters > 50f)
    }

    @Test
    fun temporaryDeviation_doesNotEscalate() {
        val detector = RouteDeviationDetector(thresholdMeters = 50f, sustainCount = 2)
        val off = JourneyPoint(latitude = 37.0005, longitude = -122.002, accuracy = 5f, timestamp = 1000)
        val on = JourneyPoint(latitude = 37.0005, longitude = -122.0, accuracy = 5f, timestamp = 2000)
        assertFalse(detector.evaluate(off, route).isDeviated)
        assertFalse(detector.evaluate(on, route).isDeviated)
        assertFalse(detector.evaluate(off, route).isDeviated) // reset by on-route
    }

    @Test
    fun emptyRoute_returnsNotDeviated() {
        val detector = RouteDeviationDetector()
        val res = detector.evaluate(JourneyPoint(1.0, 1.0, timestamp = 1), emptyList())
        assertFalse(res.isDeviated)
    }

    @Test
    fun accuracyInflatesThreshold() {
        val detector = RouteDeviationDetector(thresholdMeters = 50f, sustainCount = 1)
        // ~70m away but accuracy 100m => effective threshold 100m
        val point = JourneyPoint(latitude = 37.0005, longitude = -122.0008, accuracy = 100f, timestamp = 1000)
        val res = detector.evaluate(point, route)
        assertFalse(res.isDeviated)
    }
}
