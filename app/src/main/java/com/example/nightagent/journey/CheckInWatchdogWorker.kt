package com.example.nightagent.journey

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nightagent.journey.model.CheckInStatus
import com.example.nightagent.journey.model.JourneyStatus

class CheckInWatchdogWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val journeyId = inputData.getString(KEY_JOURNEY_ID) ?: return Result.success()
        val checkInId = inputData.getString(KEY_CHECKIN_ID) ?: return Result.success()
        val engine = JourneyEngine.getInstance(applicationContext)
        val repo = FirestoreJourneyRepository()
        val journey = repo.getJourney(journeyId).getOrNull() ?: return Result.success()
        if (journey.statusEnum() != JourneyStatus.ACTIVE) return Result.success()
        val checkIn = repo.getCheckIns(journeyId).getOrNull()?.find { it.checkInId == checkInId } ?: return Result.success()
        if (checkIn.statusEnum() != CheckInStatus.PENDING) return Result.success()
        return try {
            if (engine.state.value.journey?.journeyId != journeyId) {
                engine.restoreActiveJourney()
            }
            engine.markCheckInMissed(journeyId, checkInId)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_JOURNEY_ID = "journey_id"
        const val KEY_CHECKIN_ID = "checkin_id"
    }
}
