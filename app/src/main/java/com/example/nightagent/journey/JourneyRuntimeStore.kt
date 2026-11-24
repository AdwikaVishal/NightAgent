package com.example.nightagent.journey

import android.content.Context

class JourneyRuntimeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setActiveJourney(journeyId: String?, userId: String?) {
        prefs.edit()
            .putString(KEY_JOURNEY_ID, journeyId)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun getActiveJourneyId(): String? = prefs.getString(KEY_JOURNEY_ID, null)
    fun getActiveUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun setNextCheckIn(journeyId: String, checkInId: String, dueAt: Long) {
        prefs.edit()
            .putString(KEY_CHECKIN_JOURNEY, journeyId)
            .putString(KEY_CHECKIN_ID, checkInId)
            .putLong(KEY_CHECKIN_DUE_AT, dueAt)
            .apply()
    }

    fun getNextCheckInDueAt(): Long = prefs.getLong(KEY_CHECKIN_DUE_AT, 0L)
    fun getNextCheckInId(): String? = prefs.getString(KEY_CHECKIN_ID, null)
    fun getNextCheckInJourneyId(): String? = prefs.getString(KEY_CHECKIN_JOURNEY, null)

    fun clearCheckIn() {
        prefs.edit().remove(KEY_CHECKIN_ID).remove(KEY_CHECKIN_DUE_AT).remove(KEY_CHECKIN_JOURNEY).apply()
    }

    fun clearAll() { prefs.edit().clear().apply() }

    companion object {
        private const val PREFS = "journey_runtime"
        private const val KEY_JOURNEY_ID = "active_journey_id"
        private const val KEY_USER_ID = "active_user_id"
        private const val KEY_CHECKIN_ID = "next_checkin_id"
        private const val KEY_CHECKIN_DUE_AT = "next_checkin_due_at"
        private const val KEY_CHECKIN_JOURNEY = "next_checkin_journey_id"
    }
}
