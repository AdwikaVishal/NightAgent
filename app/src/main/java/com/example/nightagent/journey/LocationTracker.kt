package com.example.nightagent.journey

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.nightagent.journey.model.JourneyConfig
import com.example.nightagent.journey.model.JourneyPoint
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.abs

open class LocationTracker(
    private val context: Context,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var lastAccepted: JourneyPoint? = null

    open fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission() below
    open fun locationUpdates(config: JourneyConfig): Flow<JourneyPoint> = callbackFlow {
        if (!hasLocationPermission()) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, config.locationUpdateIntervalMs)
            .setMinUpdateIntervalMillis(config.fastestLocationIntervalMs)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val point = loc.toJourneyPoint() ?: continue
                    if (shouldAccept(point, config)) {
                        lastAccepted = point
                        trySend(point).isSuccess
                    }
                }
            }
        }

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { fused.removeLocationUpdates(callback) }
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission() below
    @OptIn(ExperimentalCoroutinesApi::class)
    open suspend fun getCurrentPoint(): JourneyPoint? {
        if (!hasLocationPermission()) return null
        return try {
            val cts = com.google.android.gms.tasks.CancellationTokenSource()
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(JourneyConfig().maxLocationAgeMs)
                .build()
            val loc = fused.getCurrentLocation(request, cts.token)
                .let { task ->
                    // await via simple blocking not available; use Tasks.await in IO is ok but add dep; instead use suspendCoroutine
                    kotlinx.coroutines.suspendCancellableCoroutine<Location?> { cont ->
                        task.addOnSuccessListener { cont.resume(it) {} }
                        task.addOnFailureListener { cont.resume(null) {} }
                        cont.invokeOnCancellation { cts.cancel() }
                    }
                }
            loc?.toJourneyPoint()
        } catch (_: Exception) {
            null
        }
    }

    fun reset() { lastAccepted = null }

    internal fun shouldAccept(point: JourneyPoint, config: JourneyConfig): Boolean {
        if (!point.isValid()) return false
        if (point.accuracy <= 0f || point.accuracy > config.minLocationAccuracyMeters) return false
        val now = nowMs()
        // Reject cached fixes older than the allowed age and clock-skew fixes in the future.
        if (now - point.timestamp > config.maxLocationAgeMs) return false
        if (point.timestamp - now > FUTURE_TOLERANCE_MS) return false
        val prev = lastAccepted ?: run { lastAccepted = point; return true }
        if (point.timestamp <= prev.timestamp) return false
        val dtSec = (point.timestamp - prev.timestamp) / 1000f
        if (dtSec <= 0f) return false
        val dist = distanceMeters(prev.latitude, prev.longitude, point.latitude, point.longitude)
        if (dist < 0.5f && abs(point.accuracy - prev.accuracy) < 1f) return false // duplicate/stationary jitter
        // Subtract reported accuracy so normal GPS noise is tolerated, but an
        // implausible jump is rejected regardless of how "accurate" it claims to be.
        val plausibleDistance = point.accuracy.coerceAtLeast(0f) + config.maxImplausibleSpeedMps * dtSec
        if (dist > plausibleDistance) return false
        lastAccepted = point
        return true
    }

    private fun Location.toJourneyPoint(): JourneyPoint? {
        if (latitude == 0.0 && longitude == 0.0) return null
        return JourneyPoint(
            latitude = latitude,
            longitude = longitude,
            accuracy = if (hasAccuracy()) accuracy else 999f,
            speedMps = if (hasSpeed()) speed else null,
            bearing = if (hasBearing()) bearing else null,
            altitude = if (hasAltitude()) altitude else null,
            timestamp = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            provider = provider
        )
    }

    companion object {
        private const val FUTURE_TOLERANCE_MS = 60_000L

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            return (r * c).toFloat()
        }
    }
}
