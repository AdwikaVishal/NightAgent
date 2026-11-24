package com.example.nightagent.model

data class GuardianRelationship(
    val relationshipId: String,
    val ownerUserId: String,
    val guardianUserId: String?,
    val guardianId: String,
    val status: RelationshipStatus,
    val createdAt: Long
)

enum class RelationshipStatus(val displayName: String) {
    Active("active"),
    Invited("invited"),
    Removed("removed"),
    Blocked("blocked");

    companion object {
        fun fromString(s: String): RelationshipStatus = when (s.lowercase()) {
            "active" -> Active
            "invited" -> Invited
            "removed" -> Removed
            else -> Blocked
        }
    }
}
