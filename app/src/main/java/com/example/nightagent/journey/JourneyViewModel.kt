package com.example.nightagent.journey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightagent.journey.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class JourneyViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = JourneyEngine.getInstance(application)
    private val repo: JourneyRepository = FirestoreJourneyRepository(JourneyLocalStore(application))

    val uiState: StateFlow<JourneyUiState> = engine.state

    private val _history = MutableStateFlow<List<Journey>>(emptyList())
    val history: StateFlow<List<Journey>> = _history.asStateFlow()

    private val _actionError = MutableSharedFlow<String>()
    val actionError: SharedFlow<String> = _actionError.asSharedFlow()

    init {
        viewModelScope.launch { engine.restoreActiveJourney() }
        refreshHistory()
        var lastAuthUid: String? = null
        com.example.nightagent.firebase.AuthManager.addAuthStateListener { uid ->
            if (lastAuthUid != null && uid != lastAuthUid) {
                // Sign-out or account switch: cached location/destination must not
                // leak into the next account's journey UI.
                engine.clearCachedJourney()
                refreshHistory()
            }
            lastAuthUid = uid
        }
    }

    fun retryPendingSync() {
        viewModelScope.launch {
            engine.retryPendingSync()
            refreshHistory()
        }
    }

    fun startJourney(
        destinationName: String, destLat: Double, destLng: Double, expectedArrivalTime: Long,
        checkInIntervalMinutes: Int, checkInResponseTimeoutSeconds: Int, routePoints: List<RoutePoint> = emptyList()
    ) {
        viewModelScope.launch {
            val res = engine.startJourney(destinationName, destLat, destLng, expectedArrivalTime, checkInIntervalMinutes, checkInResponseTimeoutSeconds, routePoints)
            if (res.isSuccess) {
                JourneyTrackingService.start(getApplication())
            } else {
                _actionError.emit(res.exceptionOrNull()?.message ?: "Failed to start journey")
            }
        }
    }

    fun acknowledgeCheckIn() {
        val st = uiState.value
        val j = st.journey ?: return
        val c = st.pendingCheckIn ?: return
        viewModelScope.launch { engine.acknowledgeCheckIn(j.journeyId, c.checkInId) }
    }

    fun requestHelp() {
        val st = uiState.value
        val j = st.journey ?: return
        val c = st.pendingCheckIn
        viewModelScope.launch {
            if (c != null) engine.requestHelpOnCheckIn(j.journeyId, c.checkInId)
            else {
                // Manual help without a pending check-in still carries journey context.
                com.example.nightagent.sos.SOSManager.triggerSOS(
                    getApplication(),
                    reason = "Manual help during journey ${j.journeyId}"
                )
            }
        }
    }

    fun completeJourney() {
        viewModelScope.launch {
            // JourneyEngine stops monitoring and the foreground service itself so
            // notification actions and UI actions share one lifecycle.
            engine.completeJourney()
            refreshHistory()
        }
    }

    fun cancelJourney() {
        viewModelScope.launch {
            engine.cancelJourney()
            refreshHistory()
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val uid = com.example.nightagent.firebase.AuthManager.getCurrentUserId() ?: return@launch
            _history.value = repo.getJourneyHistory(uid).getOrDefault(emptyList())
        }
    }

    fun clearError() { engine.clearError() }
}
