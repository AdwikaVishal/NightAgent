package com.nightagent.evidence.storage

/** Shared user-facing messages; no plaintext paths are ever included. */
fun encryptedEvidenceOutcomeMessage(
    mediaLabel: String,
    sourceLabel: String,
    outcome: EncryptedEvidenceOutcome
): String = when (outcome) {
    is EncryptedEvidenceOutcome.Success -> when {
        outcome.cleanupPending && outcome.cleanupStatusPending ->
            "Encrypted $mediaLabel saved and verified; plaintext cleanup and metadata status update are pending."
        outcome.cleanupPending ->
            "Encrypted $mediaLabel saved and verified; plaintext cleanup is pending."
        else -> "Encrypted $mediaLabel saved and verified."
    }

    is EncryptedEvidenceOutcome.MetadataPending ->
        "Encrypted $mediaLabel was published and verified, but local metadata registration is " +
            "pending (${metadataFailureText(outcome.reason)}). The original $sourceLabel was preserved."

    is EncryptedEvidenceOutcome.Failure -> when (outcome.reason) {
        EncryptedEvidenceFailure.STORAGE_PREPARATION ->
            "Encrypted $mediaLabel storage could not be prepared. The original $sourceLabel was preserved."
        EncryptedEvidenceFailure.CAPTURE_FILE_INVALID ->
            "The $sourceLabel file could not be verified."
        EncryptedEvidenceFailure.ENCRYPTION_FAILED ->
            "$mediaLabel encryption failed. The original $sourceLabel was preserved."
        EncryptedEvidenceFailure.VERIFICATION_FAILED ->
            "Encrypted $mediaLabel verification failed. The original $sourceLabel was preserved."
    }
}

private fun metadataFailureText(reason: MetadataOutcomeFailure): String = when (reason) {
    MetadataOutcomeFailure.DIGEST_FAILED -> "container digest could not be computed"
    MetadataOutcomeFailure.REGISTRATION_FAILED -> "database registration failed"
    MetadataOutcomeFailure.CONFLICTING_RECORD -> "a conflicting metadata record exists"
}
