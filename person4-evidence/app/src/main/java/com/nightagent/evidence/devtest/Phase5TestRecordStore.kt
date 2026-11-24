package com.nightagent.evidence.devtest

import android.content.Context

/**
 * Stores only the generated id of this harness's temporary test record.
 * It contains no key material, plaintext path or personal data.
 */
interface Phase5TestRecordStore {
    fun get(): String?
    fun save(evidenceId: String)
    fun clear()
}

/** Android implementation backed by app-private SharedPreferences. */
class SharedPreferencesPhase5TestRecordStore(
    context: Context
) : Phase5TestRecordStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    override fun get(): String? = preferences.getString(KEY_ACTIVE_ID, null)

    override fun save(evidenceId: String) {
        preferences.edit().putString(KEY_ACTIVE_ID, evidenceId).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_ACTIVE_ID).apply()
    }

    private companion object {
        const val PREFS_NAME = "phase5_devtest"
        const val KEY_ACTIVE_ID = "active_test_evidence_id"
    }
}
