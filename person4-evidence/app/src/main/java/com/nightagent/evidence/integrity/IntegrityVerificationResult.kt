package com.nightagent.evidence.integrity

/** Explicit integrity outcomes; none of them imply provenance or identity. */
enum class IntegrityStatus {
    VERIFIED,
    FILE_MISSING,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    NGE1_AUTHENTICATION_FAILED,
    METADATA_NOT_FOUND,
    VERIFICATION_ERROR
}

/** Read-only verification result. It never contains plaintext paths. */
data class IntegrityVerificationResult(
    val evidenceId: String,
    val status: IntegrityStatus,
    val detail: String? = null
)
