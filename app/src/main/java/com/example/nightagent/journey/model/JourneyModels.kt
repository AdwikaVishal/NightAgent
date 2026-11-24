package com.example.nightagent.journey.model

import com.google.firebase.firestore.PropertyName

enum class JourneyStatus {
    IDLE,
    PREPARING,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    EMERGENCY
}

enum class CheckInStatus {
    PENDING,
    ACKNOWLEDGED,
    MISSED,
    HELP_REQUESTED,
    CANCELLED
}

enum class JourneyEventType {
    MISSED_CHECKIN,
    ROUTE_DEVIATION,
    ARRIVAL_DELAY,
    USER_REQUESTED_HELP
}

data class JourneyPoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speedMps: Float? = null,
    val bearing: Float? = null,
    val altitude: Double? = null,
    val timestamp: Long = 0L,
    val provider: String? = null
) {
    fun isValid(): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0) &&
            timestamp > 0L
}

data class RoutePoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class Journey(
    val journeyId: String = "",
    val userId: String = "",
    val startLatitude: Double = 0.0,
    val startLongitude: Double = 0.0,
    val destinationLatitude: Double = 0.0,
    val destinationLongitude: Double = 0.0,
    val destinationName: String = "",
    val expectedArrivalTime: Long = 0L,
    val actualArrivalTime: Long? = null,
    val startedAt: Long = 0L,
    val completedAt: Long? = null,
    val status: String = JourneyStatus.IDLE.name,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val checkInIntervalMinutes: Int = DEFAULT_CHECKIN_INTERVAL_MIN,
    val checkInResponseTimeoutSeconds: Int = DEFAULT_CHECKIN_TIMEOUT_SEC,
    val deviationThresholdMeters: Float = DEFAULT_DEVIATION_THRESHOLD_M,
    val arrivalGraceMinutes: Int = DEFAULT_ARRIVAL_GRACE_MIN,
    val routePoints: List<RoutePoint> = emptyList(),
    @get:PropertyName("isActive")
    @set:PropertyName("isActive")
    var isActive: Boolean = false
) {
    fun statusEnum(): JourneyStatus = runCatching { JourneyStatus.valueOf(status) }.getOrDefault(JourneyStatus.IDLE)
}

data class CheckInRecord(
    val checkInId: String = "",
    val journeyId: String = "",
    val scheduledAt: Long = 0L,
    val dueAt: Long = 0L,
    val respondedAt: Long? = null,
    val status: String = CheckInStatus.PENDING.name,
    val response: String? = null,
    val escalated: Boolean = false
) {
    fun statusEnum(): CheckInStatus = runCatching { CheckInStatus.valueOf(status) }.getOrDefault(CheckInStatus.PENDING)
}

data class DeviationEvent(
    val eventId: String = "",
    val journeyId: String = "",
    val detectedAt: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val distanceFromRouteMeters: Float = 0f,
    val reason: String = "",
    val status: String = "OPEN",
    val escalated: Boolean = false
)

data class JourneyConfig(
    val checkInIntervalMinutes: Int = DEFAULT_CHECKIN_INTERVAL_MIN,
    val checkInResponseTimeoutSeconds: Int = DEFAULT_CHECKIN_TIMEOUT_SEC,
    val deviationThresholdMeters: Float = DEFAULT_DEVIATION_THRESHOLD_M,
    val deviationSustainCount: Int = DEFAULT_DEVIATION_SUSTAIN_COUNT,
    val arrivalGraceMinutes: Int = DEFAULT_ARRIVAL_GRACE_MIN,
    val locationUpdateIntervalMs: Long = DEFAULT_LOCATION_INTERVAL_MS,
    val fastestLocationIntervalMs: Long = DEFAULT_FASTEST_LOCATION_INTERVAL_MS,
    val minLocationAccuracyMeters: Float = DEFAULT_MIN_ACCURACY_M,
    val maxImplausibleSpeedMps: Float = DEFAULT_MAX_SPEED_MPS,
    val maxLocationAgeMs: Long = DEFAULT_MAX_LOCATION_AGE_MS
)

const val DEFAULT_CHECKIN_INTERVAL_MIN = 5
const val DEFAULT_CHECKIN_TIMEOUT_SEC = 120
const val DEFAULT_DEVIATION_THRESHOLD_M = 100f
const val DEFAULT_DEVIATION_SUSTAIN_COUNT = 2
const val DEFAULT_ARRIVAL_GRACE_MIN = 5
const val DEFAULT_LOCATION_INTERVAL_MS = 15_000L
const val DEFAULT_FASTEST_LOCATION_INTERVAL_MS = 5_000L
const val DEFAULT_MIN_ACCURACY_M = 80f
const val DEFAULT_MAX_SPEED_MPS = 20f // ~72km/h, implausible for walking
const val DEFAULT_MAX_LOCATION_AGE_MS = 30_000L
const val ARRIVAL_PROXIMITY_METERS = 100f
