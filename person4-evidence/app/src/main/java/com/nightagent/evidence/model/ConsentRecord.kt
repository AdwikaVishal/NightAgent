package com.nightagent.evidence.model

/**
 * Persisted state of one consent decision.
 *
 * Timestamps use epoch milliseconds. [version] records which consent terms the
 * user agreed to; a record with an older version is treated as invalid by
 * ConsentGate even if [isGranted] is true.
 */
data class ConsentRecord(
    val key: ConsentKey,
    val isGranted: Boolean = false,
    val grantedAt: Long? = null,
    val revokedAt: Long? = null,
    val version: Int = 0
) {
    companion object {
        /** Returned when nothing has ever been stored for a key. */
        fun absent(key: ConsentKey): ConsentRecord = ConsentRecord(key = key)
    }
}
