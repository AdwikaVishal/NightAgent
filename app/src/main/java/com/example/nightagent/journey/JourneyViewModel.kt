package com.example.nightagent.journey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightagent.journey.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class JourneyViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = JourneyEngine.getInstance(application)
    private val repo: JourneyRepository = FirestoreJourneyRepository()

    val uiState: StateFlow<JourneyUiState> = engine.state

    private val _history = MutableStateFlow<List<Journey>>(emptyList())
    val history: StateFlow<List<Journey>> = _history.asStateFlow()

    private val _actionError = MutableSharedFlow<String>()
    val actionError: SharedFlow<String> = _actionError.asSharedFlow()

    init {
        viewModelScope.launch { engine.restoreActiveJourney() }
        refreshHistory()
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
                // manual help without pending check-in
                com.example.nightagent.sos.SOSManager.triggerSOS(getApplication())
            }
        }
    }

    fun completeJourney() {
        viewModelScope.launch {
            engine.completeJourney()
            JourneyTrackingService.stop(getApplication())
            refreshHistory()
        }
    }

    fun cancelJourney() {
        viewModelScope.launch {
            engine.cancelJourney()
            JourneyTrackingService.stop(getApplication())
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
