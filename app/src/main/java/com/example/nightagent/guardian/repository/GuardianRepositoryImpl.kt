package com.example.nightagent.guardian.repository

import android.content.Context
import com.example.nightagent.guardian.data.local.GuardianLocalDataSource
import com.example.nightagent.guardian.domain.repository.GuardianRepository
import com.example.nightagent.model.InvitationStatus
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship
import com.example.nightagent.model.GuardianInvitation
import com.example.nightagent.guardian.utils.normalizePhoneNumber
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GuardianRepositoryImpl(
    private val appContext: Context
) : GuardianRepository {

    companion object {
        const val MAX_GUARDIANS = 5
        const val INVITATIONS_PREFS = "guardian_invitations"
        const val SOS_SESSIONS_COLLECTION = "sos_alerts"
        const val DEFAULT_ESCALATION_TIMEOUT_MS: Long = 30000L
        const val ESCALATION_STEP_MS = 10000L
    }

    private val dataSource = GuardianLocalDataSource

    override suspend fun addGuardian(guardian: Guardian): GuardianRepository.GuardianResult {
        val validatedName = dataSource.validateName(guardian.name)
        val validatedPhone = dataSource.validatePhone(guardian.phoneNumber)
        val isValidRel = guardian.relationship.displayName.isNotEmpty()

        if (!validatedName) {
            return GuardianRepository.GuardianResult.Error("Name must be between 2 and 50 characters")
        }
        if (!validatedPhone) {
            return GuardianRepository.GuardianResult.Error("Invalid phone number format")
        }
        if (!isValidRel) {
            return GuardianRepository.GuardianResult.Error("Relationship must be selected")
        }

        val existingGuardians: List<Guardian> = dataSource.getGuardians(appContext)

        if (existingGuardians.size >= MAX_GUARDIANS) {
            return GuardianRepository.GuardianResult.Error("Maximum of 5 guardians allowed")
        }

        val normalizedPhone = normalizePhoneNumber(guardian.phoneNumber)
        val phoneExists = existingGuardians.any { normalizePhoneNumber(it.phoneNumber) == normalizedPhone }

        if (phoneExists) {
            return GuardianRepository.GuardianResult.Error("A guardian with this phone number already exists")
        }

        val entity = GuardianLocalDataSource.GuardianEntity.fromDomain(guardian).copy(
            guardianId = java.util.UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val updatedGuardians = existingGuardians + entity.toDomain()

        dataSource.saveGuardians(appContext, updatedGuardians)

        return GuardianRepository.GuardianResult.Success(guardian)
    }

    override suspend fun updateGuardian(guardian: Guardian): GuardianRepository.GuardianResult {
        val existingGuardians: List<Guardian> = dataSource.getGuardians(appContext)

        val existingIndex = existingGuardians.indexOfFirst { it.guardianId == guardian.guardianId }

        if (existingIndex < 0) {
            return GuardianRepository.GuardianResult.Error("Guardian not found")
        }

        val normalizedNewPhone = normalizePhoneNumber(guardian.phoneNumber)
        val phoneNumberExists = existingGuardians.any {
            it.guardianId != guardian.guardianId && normalizePhoneNumber(it.phoneNumber) == normalizedNewPhone
        }

        if (phoneNumberExists) {
            return GuardianRepository.GuardianResult.Error("This phone number is already added")
        }

        val updatedEntity = GuardianLocalDataSource.GuardianEntity.fromDomain(guardian).copy(
            createdAt = existingGuardians[existingIndex].createdAt,
            updatedAt = System.currentTimeMillis()
        )

        val updatedGuardian = existingGuardians[existingIndex].copy(
            name = guardian.name,
            phoneNumber = guardian.phoneNumber,
            relationship = guardian.relationship,
            isPrimary = guardian.isPrimary,
            createdAt = existingGuardians[existingIndex].createdAt,
            updatedAt = System.currentTimeMillis()
        )

        val updatedGuardians = existingGuardians.toMutableList()
        updatedGuardians.set(existingIndex, updatedGuardian)

        dataSource.saveGuardians(appContext, updatedGuardians)

        return GuardianRepository.GuardianResult.Success(guardian)
    }

    override suspend fun deleteGuardian(guardianId: String): GuardianRepository.ListResult {
        val existingGuardians: List<Guardian> = dataSource.getGuardians(appContext)

        val existingIndex = existingGuardians.indexOfFirst { it.guardianId == guardianId }

        if (existingIndex < 0) {
            return GuardianRepository.ListResult.Error("Guardian not found")
        }

        val remainingGuardians = existingGuardians.subList(0, existingIndex) + existingGuardians.subList(existingIndex + 1, existingGuardians.size)

        dataSource.saveGuardians(appContext, remainingGuardians)

        return when {
            remainingGuardians.isEmpty() -> GuardianRepository.ListResult.Error("No guardians remaining")
            else -> {
                val currentPrimary = remainingGuardians.firstOrNull { it.isPrimary }
                if (currentPrimary != null) {
                    GuardianRepository.ListResult.Success(guardians = remainingGuardians)
                } else {
                    val newPrimaryGuardian = remainingGuardians.firstOrNull()
                    if (newPrimaryGuardian != null) {
                        dataSource.setPrimaryGuardian(appContext, newPrimaryGuardian.guardianId)
                    }
                    GuardianRepository.ListResult.Success(guardians = remainingGuardians)
                }
            }
        }
    }

    override suspend fun getGuardians(): GuardianRepository.ListResult {
        val guardians = dataSource.getGuardians(appContext)
        return GuardianRepository.ListResult.Success(guardians = guardians)
    }

    override suspend fun getGuardianById(guardianId: String): GuardianRepository.GuardianResult {
        val existingGuardians = dataSource.getGuardians(appContext)
        val guardian = existingGuardians.firstOrNull { it.guardianId == guardianId }

        if (guardian != null) {
            return GuardianRepository.GuardianResult.Success(guardian)
        }
        return GuardianRepository.GuardianResult.Error("Guardian not found")
    }

    override suspend fun setPrimaryGuardian(guardianId: String): GuardianRepository.GuardianResult {
        val existingGuardians: List<Guardian> = dataSource.getGuardians(appContext)

        val guardian = existingGuardians.firstOrNull { it.guardianId == guardianId }

        if (guardian == null) {
            return GuardianRepository.GuardianResult.Error("Guardian not found")
        }

        dataSource.setPrimaryGuardian(appContext, guardianId)

        val updatedGuardians = existingGuardians.map { g ->
            if (g.guardianId == guardianId) g.copy(isPrimary = true)
            else g.copy(isPrimary = false)
        }

        dataSource.saveGuardians(appContext, updatedGuardians)

        return GuardianRepository.GuardianResult.Success(guardian)
    }

    override suspend fun validateGuardian(guardian: Guardian): GuardianRepository.GuardianResult {
        val validatedName = dataSource.validateName(guardian.name)
        val validatedPhone = dataSource.validatePhone(guardian.phoneNumber)
        val isValidRel = guardian.relationship.displayName.isNotEmpty()

        val issues = mutableListOf<String>()

        if (!validatedName) issues.add("Name must be between 2 and 50 characters")
        if (!validatedPhone) issues.add("Invalid phone number format")
        if (!isValidRel) issues.add("Relationship must be selected")

        if (issues.isEmpty()) {
            return GuardianRepository.GuardianResult.Success(guardian)
        }
        return GuardianRepository.GuardianResult.Error(issues.joinToString(", "))
    }

    fun getPrimaryGuardian(): Any? {
        val primaryId = dataSource.getPrimaryGuardianId(appContext)
        val allGuardians = dataSource.getGuardians(appContext)

        val primaryGuardian = allGuardians.firstOrNull { it.guardianId == primaryId }

        if (primaryGuardian != null) {
            return GuardianRepository.GuardianResult.Success(primaryGuardian)
        } else if (allGuardians.isNotEmpty()) {
            val fallbackPrimary = allGuardians.first()
            dataSource.setPrimaryGuardian(appContext, fallbackPrimary.guardianId)
            return GuardianRepository.GuardianResult.Success(fallbackPrimary)
        }

        return GuardianRepository.GuardianResult.Error("No guardians available")
    }

    // Invitation management

    private fun getInvitationsFromPrefs(): List<GuardianInvitation> {
        val prefs = appContext.getSharedPreferences(INVITATIONS_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("invitations", null)
        if (json == null) return emptyList()
        return try {
            val type = object : TypeToken<List<GuardianInvitation>>() {}.type
            val invitations: List<GuardianInvitation> = Gson().fromJson(json, type)
            invitations
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveInvitationsToPrefs(invitations: List<GuardianInvitation>) {
        val prefs = appContext.getSharedPreferences(INVITATIONS_PREFS, Context.MODE_PRIVATE)
        val json = Gson().toJson(invitations)
        prefs.edit().putString("invitations", json).apply()
    }

    // Check for duplicate active invitations for the same guardian and user
    private fun isInvitationActive(
        invitations: List<GuardianInvitation>,
        senderUserId: String,
        guardianPhone: String
    ): Boolean {
        return invitations.any {
            it.status == InvitationStatus.Pending &&
                it.senderUserId == senderUserId &&
                it.phoneNumber == guardianPhone
        }
    }

    override suspend fun inviteGuardian(
        senderUserId: String,
        guardianName: String,
        phoneNumber: String,
        email: String?,
        relationship: Relationship
    ): GuardianRepository.GuardianResult {
        val existingInvitations = getInvitationsFromPrefs()

        // Check for duplicate active invitations
        if (isInvitationActive(existingInvitations, senderUserId, phoneNumber)) {
            return GuardianRepository.GuardianResult.Error("Active invitation already exists for this guardian")
        }

        val invitation = GuardianInvitation(
            invitationId = UUID.randomUUID().toString(),
            senderUserId = senderUserId,
            receiverUserId = null,
            guardianName = guardianName,
            phoneNumber = phoneNumber,
            email = email,
            status = InvitationStatus.Pending,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)
        )

        val guardian = Guardian(
            guardianId = invitation.invitationId,
            userId = null,
            name = invitation.guardianName,
            phoneNumber = invitation.phoneNumber,
            email = invitation.email,
            relationship = relationship,
            isPrimary = false,
            createdAt = invitation.createdAt,
            updatedAt = invitation.createdAt
        )

        val updatedInvitations = existingInvitations + invitation
        saveInvitationsToPrefs(updatedInvitations)

        return GuardianRepository.GuardianResult.Success(guardian)
    }

    override suspend fun acceptInvitation(invitationId: String): GuardianRepository.GuardianResult {
        val existingInvitations = getInvitationsFromPrefs()
        val invitationIndex = existingInvitations.indexOfFirst { it.invitationId == invitationId }

        if (invitationIndex < 0) {
            return GuardianRepository.GuardianResult.Error("Invitation not found")
        }

        val invitation = existingInvitations[invitationIndex]

        // Check expiry before accepting
        if (invitation.expiresAt < System.currentTimeMillis()) {
            // Mark as expired
            val updatedInvitation = invitation.copy(status = InvitationStatus.Expired)
            val updatedInvitations = existingInvitations.take(invitationIndex) + listOf(updatedInvitation) + existingInvitations.drop(invitationIndex + 1)
            saveInvitationsToPrefs(updatedInvitations)
            return GuardianRepository.GuardianResult.Error("Invitation has expired")
        }

        if (invitation.status != InvitationStatus.Pending) {
            return GuardianRepository.GuardianResult.Error("Invitation is not in pending state")
        }

        // Mark as accepted
        val updatedInvitation = invitation.copy(status = InvitationStatus.Accepted)
        // Create updated list by taking elements before index, adding the updated one, then adding the rest
        val updatedInvitations = existingInvitations.take(invitationIndex) + listOf(updatedInvitation) + existingInvitations.drop(invitationIndex + 1)
        saveInvitationsToPrefs(updatedInvitations)

        val acceptedGuardian = Guardian(
            guardianId = invitation.invitationId,
            userId = null,
            name = invitation.guardianName,
            phoneNumber = invitation.phoneNumber,
            email = invitation.email,
            relationship = if (invitation.status == InvitationStatus.Accepted) Relationship.Friend else Relationship.Other,
            isPrimary = false,
            createdAt = invitation.createdAt,
            updatedAt = invitation.createdAt
        )

        return GuardianRepository.GuardianResult.Success(acceptedGuardian)
    }

    override suspend fun rejectInvitation(invitationId: String): GuardianRepository.GuardianResult {
        val existingInvitations = getInvitationsFromPrefs()
        val invitationIndex = existingInvitations.indexOfFirst { it.invitationId == invitationId }

        if (invitationIndex < 0) {
            return GuardianRepository.GuardianResult.Error("Invitation not found")
        }

        val invitation = existingInvitations[invitationIndex]

        // Check expiry before rejecting
        if (invitation.expiresAt < System.currentTimeMillis()) {
            // Mark as expired
            val updatedInvitation = invitation.copy(status = InvitationStatus.Expired)
            val updatedInvitations = existingInvitations.take(invitationIndex) + listOf(updatedInvitation) + existingInvitations.drop(invitationIndex + 1)
            saveInvitationsToPrefs(updatedInvitations)
            return GuardianRepository.GuardianResult.Error("Invitation has expired")
        }

        if (invitation.status != InvitationStatus.Pending) {
            return GuardianRepository.GuardianResult.Error("Invitation is not in pending state")
        }

        // Mark as rejected
        val updatedInvitation = invitation.copy(status = InvitationStatus.Rejected)
        // Create updated list by taking elements before index, adding the updated one, then adding the rest
        val updatedInvitations = existingInvitations.take(invitationIndex) + listOf(updatedInvitation) + existingInvitations.drop(invitationIndex + 1)
        saveInvitationsToPrefs(updatedInvitations)

        val rejectedGuardian = Guardian(
            guardianId = invitation.invitationId,
            userId = null,
            name = invitation.guardianName,
            phoneNumber = invitation.phoneNumber,
            email = invitation.email,
            relationship = if (invitation.status == InvitationStatus.Rejected) Relationship.Friend else Relationship.Other,
            isPrimary = false,
            createdAt = invitation.createdAt,
            updatedAt = invitation.createdAt
        )

        return GuardianRepository.GuardianResult.Success(rejectedGuardian)
    }

    override suspend fun getInvitations(senderUserId: String): GuardianRepository.ListResult {
        val existingInvitations = getInvitationsFromPrefs()

        // Detect and mark expired pending invitations
        var updatedInvitations = existingInvitations.toMutableList()
        var needsSave = false
        for (invitation in updatedInvitations) {
            if (invitation.status == InvitationStatus.Pending && invitation.expiresAt < System.currentTimeMillis()) {
                val idx = updatedInvitations.indexOf(invitation)
                if (idx >= 0) {
                    updatedInvitations.set(idx, invitation.copy(status = InvitationStatus.Expired))
                    needsSave = true
                }
            }
        }

        if (needsSave) {
            saveInvitationsToPrefs(updatedInvitations as List<GuardianInvitation>)
        }

        // Filter invitations sent by this user
        val userInvitations = updatedInvitations.filter { it.senderUserId == senderUserId }
        val userGuardians = userInvitations.map { invitation ->
            Guardian(
                guardianId = invitation.invitationId,
                userId = null,
                name = invitation.guardianName,
                phoneNumber = invitation.phoneNumber,
                email = invitation.email,
                relationship = if (invitation.status == InvitationStatus.Accepted || invitation.status == InvitationStatus.Responding || invitation.status == InvitationStatus.Acknowledged) Relationship.Friend else Relationship.Other,
                isPrimary = false,
                createdAt = invitation.createdAt,
                updatedAt = invitation.createdAt
            )
        }
        return GuardianRepository.ListResult.Success(guardians = userGuardians)
    }

    // SOS notification service integration

    override suspend fun requestSOSNotification(
        sessionId: String,
        userId: String,
        guardianIds: List<String>,
        primaryGuardianId: String?,
        location: Pair<Double, Double>?,
        timestamp: Long
    ): GuardianRepository.ListResult {
        // TODO: In a real implementation, this would send a request to the backend
        // to notify the specified guardians of the SOS session.
        // For now, we record the request locally and return the guardian list.
        val sessionData = mapOf(
            "sessionId" to sessionId,
            "userId" to userId,
            "primaryGuardianId" to primaryGuardianId,
            "guardianIds" to guardianIds.joinToString(","),
            "location" to location,
            "timestamp" to timestamp
        )
        // In a real implementation, call Firestore or backend API here
        return GuardianRepository.ListResult.Success(guardians = emptyList())
    }

    override suspend fun recordNotificationAttempt(
        sessionId: String,
        guardianId: String,
        status: String,
        details: String?
    ): GuardianRepository.ListResult {
        // TODO: Record the notification attempt in Firestore or local storage
        // For now, just return success
        return GuardianRepository.ListResult.Success(guardians = emptyList())
    }

    override suspend fun updateGuardianAcknowledgement(
        invitationId: String,
        status: InvitationStatus
    ): GuardianRepository.GuardianResult {
        val existingInvitations = getInvitationsFromPrefs()
        val invitationIndex = existingInvitations.indexOfFirst { it.invitationId == invitationId }

        if (invitationIndex >= 0) {
            val existingInvitation = existingInvitations[invitationIndex]
            // Only set acknowledgedAt if not already set (preserve original timestamp)
            val newAcknowledgedAt = if (existingInvitation.acknowledgedAt == null) System.currentTimeMillis() else existingInvitation.acknowledgedAt
            val updatedInvitation = existingInvitation.copy(
                status = status,
                acknowledgedAt = newAcknowledgedAt
            )
            // Create updated list by taking elements before index, adding the updated one, then adding the rest
            val updatedInvitations = existingInvitations.take(invitationIndex) + listOf(updatedInvitation) + existingInvitations.drop(invitationIndex + 1)
            saveInvitationsToPrefs(updatedInvitations)
            val acknowledgedGuardian = Guardian(
                guardianId = updatedInvitation.invitationId,
                userId = null,
                name = updatedInvitation.guardianName,
                phoneNumber = updatedInvitation.phoneNumber,
                email = updatedInvitation.email,
                relationship = if (status.displayName == "acknowledged") Relationship.Friend else Relationship.Other,
                isPrimary = false,
                createdAt = updatedInvitation.createdAt,
                updatedAt = updatedInvitation.createdAt
            )
            return GuardianRepository.GuardianResult.Success(acknowledgedGuardian)
        }

        return GuardianRepository.GuardianResult.Error("Invitation not found")
    }

    // Escalation engine with configurable timeout-based guardian progression

    data class EscalationStep(
        val guardianId: String,
        val triggeredAt: Long = System.currentTimeMillis(),
        val status: String = "pending",
        var acknowledged: Boolean = false,
        val notificationAttempted: Boolean = false,
        var acknowledgedAt: Long? = null
    )


    override suspend fun initiateEscalation(
        sessionId: String,
        primaryGuardianId: String,
        allGuardianIds: List<String>,
        initialTimeoutMs: Long
    ): GuardianRepository.ListResult {

        // Remove duplicate IDs and create a mutable list of String IDs
        val orderedGuardianIds = allGuardianIds.distinct().toMutableList()

        // Move the primary guardian to the first position
        val primaryIndex = orderedGuardianIds.indexOf(primaryGuardianId)

        if (primaryIndex > 0) {
            val primaryId = orderedGuardianIds.removeAt(primaryIndex)
            orderedGuardianIds.add(0, primaryId)
        }

        // Create escalation steps
        val escalationSteps = arrayListOf<EscalationStep>()
        for (guardianId in orderedGuardianIds) {
            escalationSteps.add(EscalationStep(guardianId = guardianId, triggeredAt = System.currentTimeMillis()))
        }

        // Convert escalation steps into JSON-compatible data
        val stepsJsonArray = escalationSteps.map { step ->
            mapOf(
                "guardianId" to step.guardianId,
                "triggeredAt" to step.triggeredAt,
                "status" to step.status,
                "acknowledged" to step.acknowledged,
                "notificationAttempted" to step.notificationAttempted,
                "acknowledgedAt" to step.acknowledgedAt
            )
        }

        val stepsJson = Gson().toJson(stepsJsonArray)

        // Prepare escalation session data
        val escalationJsonMap = mapOf(
            "sessionId" to sessionId,
            "primaryGuardianId" to primaryGuardianId,
            "guardianIds" to orderedGuardianIds.joinToString(","),
            "steps" to stepsJson,
            "initialTimeoutMs" to initialTimeoutMs,
            "createdAt" to System.currentTimeMillis(),
            "smsFallbackTriggered" to false
        )

        // Persist escalation state locally
        val prefs = appContext.getSharedPreferences(
            "escalation_state",
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putString("escalation_json", Gson().toJson(escalationJsonMap))
            .apply()

        return GuardianRepository.ListResult.Success(
            guardians = emptyList()
        )
    }


    override suspend fun getEscalationStatus(sessionId: String): GuardianRepository.GuardianResult {
        val prefs = appContext.getSharedPreferences("escalation_state", Context.MODE_PRIVATE)
        val json = prefs.getString("escalation_json", null)
        if (json == null) {
            return GuardianRepository.GuardianResult.Error("No active escalation session found")
        }
        val guardians = dataSource.getGuardians(appContext)
        val primaryGuardianId = dataSource.getPrimaryGuardianId(appContext)
        val guardian = if (guardians.isNotEmpty()) {
            if (primaryGuardianId != null) {
                guardians.firstOrNull { it.guardianId == primaryGuardianId }!!
            } else {
                guardians.first()!!
            }
        } else {
            throw Exception("No guardians available")
        }
        return GuardianRepository.GuardianResult.Success(guardian)
    }

    override suspend fun emergencySMSFallback(
        sessionId: String,
        userPhone: String,
        guardianIds: List<String>
    ): GuardianRepository.ListResult {
        // TODO: Trigger SMS fallback to all guardians
        // For now, just record the fallback request
        // SMS fallback is handled by SOSManager.sendEmergencySMS() when FCM fails
        // This method records the fallback request for backend triggering
        val smsData = mapOf(
            "sessionId" to sessionId,
            "userPhone" to userPhone,
            "guardianIds" to guardianIds.joinToString(","),
            "triggeredAt" to System.currentTimeMillis()
        )

        // In a real implementation, trigger backend SMS service here
        return GuardianRepository.ListResult.Success(guardians = emptyList())
    }

    override suspend fun getCurrentSOSSession(userId: String): GuardianRepository.GuardianResult {
        // TODO: Retrieve the current SOS session from Firestore
        // For now, return an error indicating this needs backend integration
        return GuardianRepository.GuardianResult.Error("SOS session retrieval requires backend configuration")
    }
}