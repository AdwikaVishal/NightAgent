package com.nightagent.evidence.consent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val DATA_STORE_NAME = "evidence_consent_preferences"

/** Single DataStore instance backed by the application context. */
private val Context.consentPreferences: DataStore<Preferences> by preferencesDataStore(
    name = DATA_STORE_NAME
)

/**
 * Preferences DataStore backed consent storage.
 *
 * Accepting a [DataStore] directly keeps this class unit-testable with an
 * on-disk DataStore created by PreferenceDataStoreFactory.
 */
class PersistentConsentRepository(
    private val dataStore: DataStore<Preferences>
) : ConsentRepository {

    companion object {
        fun create(context: Context): PersistentConsentRepository =
            PersistentConsentRepository(context.applicationContext.consentPreferences)
    }

    override fun observe(key: ConsentKey): Flow<ConsentRecord> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { preferences -> preferences.read(key) }

    override fun observeAll(): Flow<Map<ConsentKey, ConsentRecord>> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { preferences ->
                ConsentKey.ALL.associateWith { key -> preferences.read(key) }
            }

    override suspend fun get(key: ConsentKey): ConsentRecord = observe(key).first()

    /**
     * Records a fresh grant against the current terms version. The previous
     * revocation timestamp is cleared, while the original grant timestamp is
     * replaced to reflect this new decision.
     */
    override suspend fun grant(key: ConsentKey) {
        dataStore.edit { preferences ->
            preferences[grantedKey(key)] = true
            preferences[grantedAtKey(key)] = System.currentTimeMillis()
            preferences.remove(revokedAtKey(key))
            preferences[versionKey(key)] = ConsentTerms.CURRENT_VERSION
        }
    }

    /**
     * Revokes a decision. DataStore.edit() completes only after the change is
     * committed, so the revocation is durable when this call returns. Grant
     * history and version are preserved for auditing.
     */
    override suspend fun revoke(key: ConsentKey) {
        dataStore.edit { preferences ->
            preferences[grantedKey(key)] = false
            preferences[revokedAtKey(key)] = System.currentTimeMillis()
        }
    }

    private fun Preferences.read(key: ConsentKey): ConsentRecord = ConsentRecord(
        key = key,
        isGranted = this[grantedKey(key)] ?: false,
        grantedAt = this[grantedAtKey(key)],
        revokedAt = this[revokedAtKey(key)],
        version = this[versionKey(key)] ?: 0
    )
}

private fun grantedKey(key: ConsentKey) =
    booleanPreferencesKey("${key.storageKey}.granted")

private fun grantedAtKey(key: ConsentKey) =
    longPreferencesKey("${key.storageKey}.granted_at")

private fun revokedAtKey(key: ConsentKey) =
    longPreferencesKey("${key.storageKey}.revoked_at")

private fun versionKey(key: ConsentKey) =
    intPreferencesKey("${key.storageKey}.version")
