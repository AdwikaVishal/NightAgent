package com.example.nightagent.guardian.repository

import android.content.Context
import android.util.Log
import com.example.nightagent.guardian.data.local.GuardianLocalDataSource
import com.example.nightagent.guardian.domain.repository.GuardianRepository
import com.example.nightagent.model.InvitationStatus
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship
import com.example.nightagent.model.GuardianInvitation
import com.example.nightagent.guardian.utils.normalizePhoneNumber
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.tasks.await

class GuardianRepositoryImpl(
    private val appContext: Context
) : GuardianRepository {

    companion object {
        private const val TAG = "GuardianRepository"
        const val MAX_GUARDIANS = 5
        const val INVITATIONS_PREFS = "guardian_invitations"
        const val SOS_SESSIONS_COLLECTION = "sos_alerts"
        const val SOS_NOTIFICATION_PREFS = "sos_notification_attempts"
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

        val removed = existingGuardians[existingIndex]
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid != null && !removed.userId.isNullOrBlank()) {
            // Revoke server-side access before removing the local entry. Failure
            // is surfaced so an orphaned link is not silently left in place.
            when (val revoke = unlinkGuardian(currentUid, removed.userId!!)) {
                is GuardianRepository.ListResult.Error -> return revoke
                else -> Unit
            }
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
        val guardians = syncLinkedGuardians(dataSource.getGuardians(appContext))
        return GuardianRepository.ListResult.Success(guardians = guardians)
    }

    /**
     * Resolves locally stored guardian phone numbers to registered Firebase
     * accounts through the trusted callable. The callable also writes the
     * owner's `users/{uid}.guardianUids`, which sendEmergencyNotification and
     * the journey rules require before delivery is permitted.
     */
    private suspend fun syncLinkedGuardians(
        guardians: List<Guardian>
    ): List<Guardian> {
        if (FirebaseAuth.getInstance().currentUser?.uid == null) return guardians
        val phones = guardians.map { it.phoneNumber }.distinct().take(MAX_GUARDIANS)
        return try {
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("syncGuardianLinks")
                .call(mapOf("phoneNumbers" to phones))
                .await()
            val mapping = (result?.data as? Map<*, *>)?.get("phoneToUid") as? Map<*, *>
                ?: return guardians
            val updated = guardians.map { guardian ->
                val uid = mapping[guardian.phoneNumber] as? String
                if (uid.isNullOrBlank()) guardian else guardian.copy(userId = uid)
            }
            if (updated != guardians) dataSource.saveGuardians(appContext, updated)
            updated
        } catch (e: Exception) {
            Log.w(TAG, "Guardian link sync failed: ${e.message}")
            guardians
        }
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
        if (FirebaseAuth.getInstance().currentUser?.uid == null) {
            return GuardianRepository.GuardianResult.Error("Sign in before inviting a guardian")
        }
        val existingInvitations = getInvitationsFromPrefs()
        if (isInvitationActive(existingInvitations, senderUserId, phoneNumber)) {
            return GuardianRepository.GuardianResult.Error("Active invitation already exists for this guardian")
        }

        val payload = mutableMapOf<String, Any?>(
            "phoneNumber" to phoneNumber,
            "displayName" to guardianName
        )
        if (!email.isNullOrBlank()) payload["email"] = email

        val response = try {
            FirebaseFunctions.getInstance()
                .getHttpsCallable("inviteGuardian")
                .call(payload)
                .await()
                ?.data as? Map<*, *>
        } catch (e: Exception) {
            return GuardianRepository.GuardianResult.Error(
                "Invitation was not created: ${e.message ?: "unknown error"}"
            )
        } ?: return GuardianRepository.GuardianResult.Error("Invitation response was empty")

        val inviteId = response["inviteId"] as? String
            ?: return GuardianRepository.GuardianResult.Error("Invitation response was invalid")
        val guardianUid = response["guardianUid"] as? String
        val notified = response["notified"] == true

        val invitation = GuardianInvitation(
            invitationId = inviteId,
            senderUserId = senderUserId,
            receiverUserId = guardianUid,
            guardianName = guardianName,
            phoneNumber = phoneNumber,
            email = email,
            status = InvitationStatus.Pending,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)
        )
        saveInvitationsToPrefs(existingInvitations + invitation)

        val guardian = Guardian(
            guardianId = inviteId,
            userId = guardianUid,
            name = guardianName,
            phoneNumber = phoneNumber,
            email = email,
            relationship = relationship,
            isPrimary = false,
            createdAt = invitation.createdAt,
            updatedAt = invitation.createdAt
        )
        val message = if (notified) {
            "Guardian invitation sent and push delivery confirmed"
        } else {
            "Guardian invitation created; push delivery was not confirmed"
        }
        Log.i(TAG, message)
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
        if (sessionId.isBlank() || userId.isBlank()) {
            return GuardianRepository.ListResult.Error("Session and user are required for guardian notification")
        }
        val recipients = guardianIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (recipients.isEmpty()) {
            return GuardianRepository.ListResult.Error("No linked guardians available for push delivery")
        }

        val prefs = appContext.getSharedPreferences(SOS_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("requested_$sessionId", false)) {
            return GuardianRepository.ListResult.Error("Guardian notification already requested for this session")
        }

        val payload = mutableMapOf<String, Any?>(
            "sosSessionId" to sessionId,
            "ownerUid" to userId,
            "guardianUids" to recipients,
            "primaryGuardianUid" to primaryGuardianId,
            "timestamp" to timestamp
        )
        if (location != null) {
            payload["latitude"] = location.first
            payload["longitude"] = location.second
        }

        return try {
            // Only the callable reads guardian FCM tokens and sends messages.
            // The client never handles a token and never claims unconfirmed delivery.
            val response = FirebaseFunctions.getInstance()
                .getHttpsCallable("sendEmergencyNotification")
                .call(payload)
                .await()
            val parsed = GuardianNotificationResponse.parse(response?.data)
            val deliveredIds = parsed.getOrNull()
            if (deliveredIds == null) {
                return GuardianRepository.ListResult.Error(
                    parsed.exceptionOrNull()?.message ?: "Guardian delivery not confirmed"
                )
            }
            prefs.edit().putBoolean("requested_$sessionId", true).apply()
            val confirmed = dataSource.getGuardians(appContext)
                .filter { it.userId in deliveredIds }
            GuardianRepository.ListResult.Success(guardians = confirmed)
        } catch (e: Exception) {
            GuardianRepository.ListResult.Error("Guardian notification failed: ${e.message ?: "unknown error"}")
        }
    }

    override suspend fun acknowledgeEmergency(
        sessionId: String,
        ownerUid: String,
        guardianUid: String?
    ): GuardianRepository.ListResult {
        if (sessionId.isBlank() || ownerUid.isBlank()) {
            return GuardianRepository.ListResult.Error("Session and owner are required")
        }
        return try {
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("acknowledgeEmergency")
                .call(
                    mapOf(
                        "sosSessionId" to sessionId,
                        "ownerUid" to ownerUid,
                        "guardianUid" to guardianUid
                    )
                )
                .await()
            val status = (result?.data as? Map<*, *>)?.get("status") as? String
            if (status == "acknowledged") {
                GuardianRepository.ListResult.Success(guardians = emptyList())
            } else {
                GuardianRepository.ListResult.Error("Acknowledgement not confirmed")
            }
        } catch (e: Exception) {
            GuardianRepository.ListResult.Error("Acknowledgement failed: ${e.message ?: "unknown error"}")
        }
    }

    override suspend fun escalateEmergency(
        sessionId: String,
        ownerUid: String
    ): GuardianRepository.ListResult {
        if (sessionId.isBlank() || ownerUid.isBlank()) {
            return GuardianRepository.ListResult.Error("Session and owner are required")
        }
        return try {
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("escalateEmergency")
                .call(mapOf("sosSessionId" to sessionId, "ownerUid" to ownerUid))
                .await()
            val status = (result?.data as? Map<*, *>)?.get("status") as? String
            if (status == "escalated") {
                GuardianRepository.ListResult.Success(guardians = emptyList())
            } else {
                GuardianRepository.ListResult.Error("Escalation not confirmed")
            }
        } catch (e: Exception) {
            GuardianRepository.ListResult.Error("Escalation failed: ${e.message ?: "unknown error"}")
        }
    }

    override suspend fun recordNotificationAttempt(
        sessionId: String,
        guardianId: String,
        status: String,
        details: String?
    ): GuardianRepository.ListResult {
        if (sessionId.isBlank() || guardianId.isBlank() || status.isBlank()) {
            return GuardianRepository.ListResult.Error("Session, guardian and status are required")
        }
        return try {
            val prefs = appContext.getSharedPreferences(SOS_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            val key = "attempt_${sessionId}_${guardianId}_${System.currentTimeMillis()}"
            prefs.edit().putString(key, "$status|${details?.take(300).orEmpty()}").apply()
            GuardianRepository.ListResult.Success(guardians = emptyList())
        } catch (e: Exception) {
            GuardianRepository.ListResult.Error("Could not record notification attempt: ${e.message}")
        }
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

    override suspend fun loadIncomingInvitations(): GuardianRepository.ListResult {
        if (FirebaseAuth.getInstance().currentUser?.uid == null) {
            return GuardianRepository.ListResult.Error("Sign in to view guardian invitations")
        }
        return try {
            val data = FirebaseFunctions.getInstance()
                .getHttpsCallable("getGuardianInvitations")
                .call(emptyMap<String, Any>())
                .await()
                ?.data as? Map<*, *>
            val invitations = (data?.get("invitations") as? List<*>) ?: emptyList<Any>()
            val guardians = invitations.mapNotNull { entry ->
                val item = entry as? Map<*, *> ?: return@mapNotNull null
                val inviteId = item["inviteId"] as? String ?: return@mapNotNull null
                Guardian(
                    guardianId = inviteId,
                    userId = item["ownerUid"] as? String,
                    name = item["displayName"] as? String ?: "Guardian invitation",
                    phoneNumber = item["phoneNumber"] as? String ?: "",
                    email = item["email"] as? String,
                    relationship = Relationship.Other,
                    isPrimary = false,
                    createdAt = (item["createdAt"] as? Number)?.toLong() ?: 0L,
                    updatedAt = (item["expiresAt"] as? Number)?.toLong() ?: 0L
                )
            }
            GuardianRepository.ListResult.Success(guardians)
        } catch (e: Exception) {
            GuardianRepository.ListResult.Error("Could not load invitations: ${e.message ?: "unknown error"}")
        }
    }

    override suspend fun respondToInvitation(
        inviteId: String,
        accept: Boolean
    ): GuardianRepository.GuardianResult {
        if (inviteId.isBlank()) {
            return GuardianRepository.GuardianResult.Error("Invitation id is required")
        }
        return try {
            val data = FirebaseFunctions.getInstance()
                .getHttpsCallable("respondGuardianInvite")
                .call(mapOf("inviteId" to inviteId, "accept" to accept))
                .await()
                ?.data as? Map<*, *>
            when (data?.get("status") as? String) {
                "accepted", "rejected" -> GuardianRepository.GuardianResult.Success(
                    Guardian(
                        guardianId = inviteId,
                        userId = data["ownerUid"] as? String,
                        name = "Guardian invitation",
                        phoneNumber = "",
                        email = null,
                        relationship = Relationship.Other,
                        isPrimary = false,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                else -> GuardianRepository.GuardianResult.Error("Invitation response was not confirmed")
            }
        } catch (e: Exception) {
            GuardianRepository.GuardianResult.Error("Could not answer invitation: ${e.message ?: "unknown error"}")
        }
    }

    override suspend fun unlinkGuardian(
        ownerUid: String,
        guardianUid: String
    ): GuardianRepository.ListResult {
        if (ownerUid.isBlank() || guardianUid.isBlank()) {
            return GuardianRepository.ListResult.Error("Owner and guardian are required")
        }
        return try {
            val data = FirebaseFunctions.getInstance()
                .getHttpsCallable("unlinkGuardian")
                .call(mapOf("ownerUid" to ownerUid, "guardianUid" to guardianUid))
                .await()
                ?.data as? Map<*, *>
            if (data?.get("status") == "unlinked") {
                GuardianRepository.ListResult.Success(guardians = emptyList())
            } else {
                GuardianRepository.ListResult.Error("Unlink was not confirmed")
            }
        } catch (e: Exception) {
            GuardianRepository.ListResult.Error("Unlink failed: ${e.message ?: "unknown error"}")
        }
    }
}