package com.nightagent.evidence.metadata

import com.nightagent.evidence.model.EvidenceType

/** Local registration/storage state for an encrypted evidence record. */
enum class RegistrationStatus {
    STORED,
    PENDING_CLEANUP
}

/**
 * Domain representation of one locally registered encrypted evidence container.
 *
 * Paths are always relative to the app-private files directory and refer only
 * to published `.ng1` containers. Plaintext staging paths and contents are never
 * part of this model.
 */
data class EvidenceMetadata(
    val evidenceId: String,
    val mediaType: EvidenceType,
    val originalExtension: String,
    val createdAtMillis: Long,
    val containerRelativePath: String,
    val containerSizeBytes: Long,
    val sha256: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val registrationStatus: RegistrationStatus = RegistrationStatus.STORED
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
