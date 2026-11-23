package com.nightagent.evidence.testutil

import com.nightagent.evidence.consent.ConsentRepository
import com.nightagent.evidence.consent.ConsentTerms
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory ConsentRepository used to test ConsentGate and authorization logic. */
class FakeConsentRepository : ConsentRepository {

    private val records = MutableStateFlow(emptyMap<ConsentKey, ConsentRecord>())

    override fun observe(key: ConsentKey): Flow<ConsentRecord> =
        records.map { it[key] ?: ConsentRecord.absent(key) }

    override fun observeAll(): Flow<Map<ConsentKey, ConsentRecord>> =
        records.map { stored -> ConsentKey.ALL.associateWith { stored[it] ?: ConsentRecord.absent(it) } }

    override suspend fun get(key: ConsentKey): ConsentRecord = observe(key).first()

    override suspend fun grant(key: ConsentKey) {
        records.value = records.value + (
            key to ConsentRecord(
                key = key,
                isGranted = true,
                grantedAt = System.currentTimeMillis(),
                version = ConsentTerms.CURRENT_VERSION
            )
        )
    }

    override suspend fun revoke(key: ConsentKey) {
        val previous = get(key)
        records.value = records.value + (
            key to previous.copy(
                isGranted = false,
                revokedAt = System.currentTimeMillis()
            )
        )
    }

    /** Allows tests to seed arbitrary records, including outdated ones. */
    fun put(record: ConsentRecord) {
        records.value = records.value + (record.key to record)
    }
}
