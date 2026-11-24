package com.nightagent.evidence.storage

/**
 * Sanitized, user-visible diagnostic attached to a failed (or metadata-pending)
 * publication. It exposes only identifiers needed to locate the failure:
 * the media operation, the exact failing stage, the exception class, a numeric
 * error code when available, and whether publication / metadata registration
 * were reached. It never contains paths, keys, plaintext or raw messages.
 */
data class EvidenceFailureDiagnostic(
    val operation: String,
    val stage: String,
    val exceptionClass: String,
    val errorCode: String?,
    val targetPublished: Boolean,
    val metadataRegistrationAttempted: Boolean
)

/** The diagnostic carried by an outcome, or null for success / no recorder. */
fun EncryptedEvidenceOutcome.diagnosticOrNull(): EvidenceFailureDiagnostic? = when (this) {
    is EncryptedEvidenceOutcome.Failure -> diagnostic
    is EncryptedEvidenceOutcome.MetadataPending -> diagnostic
    is EncryptedEvidenceOutcome.Success -> null
}

/**
 * Renders the diagnostic as plain, sanitized text for the in-app developer
 * section. No private paths, key material, plaintext or raw exception messages
 * are ever included.
 */
fun EvidenceFailureDiagnostic.toDisplayText(): String = buildString {
    append("Operation: ").append(operation).append('\n')
    append("Failed stage: ").append(stage).append('\n')
    append("Exception: ").append(exceptionClass).append('\n')
    append("Error code: ").append(errorCode ?: "none").append('\n')
    append("Encrypted target published: ").append(if (targetPublished) "yes" else "no").append('\n')
    append("Metadata registration attempted: ")
    append(if (metadataRegistrationAttempted) "yes" else "no")
}
