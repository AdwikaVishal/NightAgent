package com.example.nightagent.guardian.domain.repository

import com.example.nightagent.model.InvitationStatus
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship
import kotlinx.coroutines.flow.Flow

interface GuardianRepository {

    sealed class GuardianResult {
        data class Success(val guardian: Guardian) : GuardianResult()
        data class Error(val message: String) : GuardianResult()
    }

    sealed class ListResult {
        data class Success(val guardians: List<Guardian>) : ListResult()
        data class Error(val message: String) : ListResult()
    }

    suspend fun addGuardian(guardian: Guardian): GuardianResult
    suspend fun updateGuardian(guardian: Guardian): GuardianResult
    suspend fun deleteGuardian(guardianId: String): ListResult
    suspend fun getGuardians(): ListResult
    suspend fun getGuardianById(guardianId: String): GuardianResult
    suspend fun setPrimaryGuardian(guardianId: String): GuardianResult
    suspend fun validateGuardian(guardian: Guardian): GuardianResult
    suspend fun requestSOSNotification(sessionId: String, userId: String, guardianIds: List<String>, primaryGuardianId: String?, location: Pair<Double, Double>?, timestamp: Long): ListResult
    suspend fun recordNotificationAttempt(sessionId: String, guardianId: String, status: String, details: String?): ListResult
    suspend fun getCurrentSOSSession(userId: String): GuardianResult
    suspend fun updateGuardianAcknowledgement(invitationId: String, status: InvitationStatus): GuardianResult
    suspend fun initiateEscalation(sessionId: String, primaryGuardianId: String, allGuardianIds: List<String>, initialTimeoutMs: Long): ListResult
    suspend fun getEscalationStatus(sessionId: String): GuardianResult
    suspend fun emergencySMSFallback(sessionId: String, userPhone: String, guardianIds: List<String>): ListResult
    suspend fun inviteGuardian(senderUserId: String, guardianName: String, phoneNumber: String, email: String?, relationship: Relationship): GuardianResult
    suspend fun acceptInvitation(invitationId: String): GuardianResult
    suspend fun rejectInvitation(invitationId: String): GuardianResult
    suspend fun getInvitations(senderUserId: String): ListResult
}