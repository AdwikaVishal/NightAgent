package com.nightagent.evidence.consent

import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for consent decisions. Implementations must persist data so
 * consent survives process death and app restarts.
 */
interface ConsentRepository {
    /** Emits the current record for [key], including an absent record initially. */
    fun observe(key: ConsentKey): Flow<ConsentRecord>

    /** Emits every managed consent decision, useful for settings UI. */
    fun observeAll(): Flow<Map<ConsentKey, ConsentRecord>>

    suspend fun get(key: ConsentKey): ConsentRecord

    suspend fun grant(key: ConsentKey)

    suspend fun revoke(key: ConsentKey)
}
