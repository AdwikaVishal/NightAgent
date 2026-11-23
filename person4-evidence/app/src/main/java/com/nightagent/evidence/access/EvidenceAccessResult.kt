package com.nightagent.evidence.access

import com.nightagent.evidence.integrity.IntegrityStatus

/** Stage at which a request for temporary plaintext access failed. */
enum class EvidenceAccessStage {
    INTEGRITY_CHECK,
    METADATA,
    CONTAINER_PATH,
    PREPARE_TEMPORARY,
    DECRYPT
}

/**
 * Outcome of requesting scoped access to decrypted evidence.
 *
 * A successful result is [Ready] and carries a short-lived token. Every other
 * outcome is explicit and never claims that decryption or verification
 * succeeded.
 */
sealed interface EvidenceAccessResult {
    data class Ready(val token: EvidenceAccessToken) : EvidenceAccessResult

    /** Integrity/authentication did not pass; no plaintext was produced. */
    data class Denied(val status: IntegrityStatus) : EvidenceAccessResult

    data class Failed(
        val stage: EvidenceAccessStage,
        val cause: Throwable? = null
    ) : EvidenceAccessResult
}
