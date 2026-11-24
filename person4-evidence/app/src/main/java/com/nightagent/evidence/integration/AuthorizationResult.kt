package com.nightagent.evidence.integration

import com.nightagent.evidence.model.EvidenceOperation

/** Outcome of the combined consent + Android permission check. */
sealed interface AuthorizationResult {
    data object Authorized : AuthorizationResult

    data class Denied(
        val operation: EvidenceOperation,
        val reason: Reason
    ) : AuthorizationResult

    enum class Reason {
        CONSENT_NOT_GRANTED,
        CONSENT_REVOKED,
        CONSENT_OUTDATED,
        PERMISSION_MISSING,
        PERMISSION_PERMANENTLY_DENIED
    }
}
