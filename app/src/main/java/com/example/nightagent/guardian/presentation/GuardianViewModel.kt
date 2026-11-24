package com.example.nightagent.guardian.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nightagent.guardian.domain.repository.GuardianRepository
import com.example.nightagent.firebase.AuthManager
import com.example.nightagent.model.InvitationStatus
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GuardianUiState(
    val isLoading: Boolean = false,
    val guardians: List<Guardian> = emptyList(),
    val primaryGuardian: Guardian? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class GuardianViewModel(
    private val repository: GuardianRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuardianUiState())
    val uiState: StateFlow<GuardianUiState> = _uiState.asStateFlow()

    fun loadGuardians() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.getGuardians()
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        guardians = result.guardians,
                        primaryGuardian = result.guardians.firstOrNull { it.isPrimary },
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun addGuardian(guardian: Guardian) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.addGuardian(guardian)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Guardian added successfully",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun inviteGuardian(guardianName: String, phoneNumber: String, email: String?, relationship: Relationship) {
        val senderUserId = AuthManager.getCurrentUserId() ?: "current_user"
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.inviteGuardian(senderUserId, guardianName, phoneNumber, email, relationship)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Invitation created",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun updateGuardian(guardian: Guardian) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.updateGuardian(guardian)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Guardian updated successfully",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun deleteGuardian(guardianId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.deleteGuardian(guardianId)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        guardians = result.guardians,
                        primaryGuardian = result.guardians.firstOrNull { it.isPrimary },
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun setPrimaryGuardian(guardianId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.setPrimaryGuardian(guardianId)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Primary guardian updated",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun requestSOSNotification(
        sessionId: String,
        userId: String,
        guardianIds: List<String>,
        primaryGuardianId: String?,
        location: Pair<Double, Double>?,
        timestamp: Long
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.requestSOSNotification(
                sessionId, userId, guardianIds, primaryGuardianId, location, timestamp
            )
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "SOS notification request sent",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun recordNotificationAttempt(
        sessionId: String,
        guardianId: String,
        status: String,
        details: String?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.recordNotificationAttempt(
                sessionId, guardianId, status, details
            )
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Notification attempt recorded",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun getCurrentSOSSession(userId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.getCurrentSOSSession(userId)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "SOS session retrieved",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun updateGuardianAcknowledgement(
        invitationId: String,
        status: InvitationStatus
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.updateGuardianAcknowledgement(invitationId, status)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Guardian acknowledgement updated to ${status.displayName}",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun initiateEscalation(
        sessionId: String,
        primaryGuardianId: String,
        allGuardianIds: List<String>,
        initialTimeoutMs: Long = 30000
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.initiateEscalation(
                sessionId, primaryGuardianId, allGuardianIds, initialTimeoutMs
            )
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Escalation initiated",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun getEscalationStatus(sessionId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.getEscalationStatus(sessionId)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Escalation status retrieved",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun emergencySMSFallback(
        sessionId: String,
        userPhone: String,
        guardianIds: List<String>
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.emergencySMSFallback(sessionId, userPhone, guardianIds)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "SMS fallback triggered",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    fun validateGuardian(guardian: Guardian) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.validateGuardian(guardian)
            when (result) {
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Guardian data is valid",
                        isLoading = false
                    )
                is com.example.nightagent.guardian.domain.repository.GuardianRepository.GuardianResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message,
                        isLoading = false
                    )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val repository: GuardianRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GuardianViewModel::class.java)) {
                return GuardianViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}