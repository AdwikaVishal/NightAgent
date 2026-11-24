package com.example.nightagent.journey

import com.example.nightagent.journey.model.JourneyPoint
import com.example.nightagent.journey.model.RoutePoint
import kotlin.math.cos

class RouteDeviationDetector(
    private val thresholdMeters: Float = 100f,
    private val sustainCount: Int = 2
) {
    private var consecutiveDeviations = 0

    fun reset() { consecutiveDeviations = 0 }

    fun evaluate(point: JourneyPoint, route: List<RoutePoint>): DeviationResult {
        if (route.size < 2) return DeviationResult(false, 0f, "No planned route available")
        val distance = distanceToRouteMeters(point.latitude, point.longitude, route)
        val effectiveThreshold = thresholdMeters + (point.accuracy.coerceAtLeast(0f) * 0.5f)
        val deviated = distance > effectiveThreshold
        consecutiveDeviations = if (deviated) consecutiveDeviations + 1 else 0
        val sustained = consecutiveDeviations >= sustainCount
        return DeviationResult(
            isDeviated = sustained,
            distanceMeters = distance,
            reason = if (sustained) "Sustained deviation ${"%.0f".format(distance)}m from route" else if (deviated) "Temporary deviation" else "On route"
        )
    }

    data class DeviationResult(val isDeviated: Boolean, val distanceMeters: Float, val reason: String)

    companion object {
        fun distanceToRouteMeters(lat: Double, lon: Double, route: List<RoutePoint>): Float {
            var min = Float.MAX_VALUE
            for (i in 0 until route.size - 1) {
                val d = distanceToSegmentMeters(lat, lon, route[i], route[i + 1])
                if (d < min) min = d
            }
            return if (min == Float.MAX_VALUE) 0f else min
        }

        // Equirectangular projection approximation - adequate for short walking distances.
        internal fun distanceToSegmentMeters(lat: Double, lon: Double, a: RoutePoint, b: RoutePoint): Float {
            val latRad = Math.toRadians(lat)
            val mPerDegLat = 111320.0
            val mPerDegLon = 111320.0 * cos(latRad).coerceAtLeast(0.01)

            val ax = 0.0; val ay = 0.0
            val bx = (b.longitude - a.longitude) * mPerDegLon
            val by = (b.latitude - a.latitude) * mPerDegLat
            val px = (lon - a.longitude) * mPerDegLon
            val py = (lat - a.latitude) * mPerDegLat

            val ab2 = bx * bx + by * by
            val t = if (ab2 <= 0.0) 0.0 else ((px * bx + py * by) / ab2).coerceIn(0.0, 1.0)
            val cx = ax + t * bx
            val cy = ay + t * by
            val dx = px - cx
            val dy = py - cy
            return kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
        }
    }
}
