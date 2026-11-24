package com.example.nightagent.model

data class GuardianInvitation(
    val invitationId: String,
    val senderUserId: String,
    val receiverUserId: String?,
    val guardianName: String,
    val phoneNumber: String,
    val email: String?,
    val status: InvitationStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val acknowledgedAt: Long? = null
)

enum class InvitationStatus(val displayName: String) {
    Pending("pending"),
    Accepted("accepted"),
    Rejected("rejected"),
    Expired("expired"),
    Sent("sent"),
    Delivered("delivered"),
    Acknowledged("acknowledged"),
    Responding("responding"),
    Resolved("resolved"),
    Failed("failed");

    companion object {
        fun fromString(s: String): InvitationStatus = when (s.lowercase()) {
            "accepted" -> Accepted
            "rejected" -> Rejected
            "expired" -> Expired
            "sent" -> Sent
            "delivered" -> Delivered
            "acknowledged" -> Acknowledged
            "responding" -> Responding
            "resolved" -> Resolved
            "failed" -> Failed
            else -> Pending
        }
    }
}
