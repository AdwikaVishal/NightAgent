package com.example.nightagent.model

enum class SOSState {
    IDLE,
    INITIATED,
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED,
    CANCELLED
}

enum class TriggerType {
    MANUAL,
    SHAKE,
    VOICE,
    POWER_BUTTON,
    STEALTH,
    SAFE_WALK
}

data class EmergencySession(
    val id: String,
    val userId: String,
    val state: SOSState,
    val triggerType: TriggerType,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    val idempotencyKey: String,
    val failureReason: String? = null
)
