package com.nightagent.evidence.integration

import com.nightagent.evidence.storage.EncryptedEvidenceFailure
import com.nightagent.evidence.storage.MetadataOutcomeFailure

/**
 * Host-facing result of publishing an already captured plaintext file.
 *
 * A [Published] result is the only point at which the host receives the stable
 * evidence id. Failures never report success, and the existing publication
 * pipeline keeps the plaintext whenever encryption, verification, SHA-256 or
 * metadata registration did not all succeed.
 */
sealed interface PublicationResult {

    data class Published(
        val evidenceId: String,
        val encryptedName: String,
        val cleanupPending: Boolean,
        val cleanupStatusPending: Boolean = false
    ) : PublicationResult

    /** Container was registered but the id could not be read back; treat as inconsistent. */
    data class MetadataLookupFailed(val encryptedName: String) : PublicationResult

    data class PendingMetadata(
        val encryptedName: String,
        val reason: MetadataOutcomeFailure
    ) : PublicationResult

    data class Failed(val reason: EncryptedEvidenceFailure) : PublicationResult

    data class Denied(val reason: AuthorizationResult.Reason) : PublicationResult
}
