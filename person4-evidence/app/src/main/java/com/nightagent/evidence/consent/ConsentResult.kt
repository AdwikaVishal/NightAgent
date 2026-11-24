package com.nightagent.evidence.consent

import com.nightagent.evidence.model.ConsentKey

/** Outcome of checking a consent decision. */
sealed interface ConsentResult {
    data object Granted : ConsentResult

    data class Denied(
        val key: ConsentKey,
        val reason: Reason
    ) : ConsentResult

    enum class Reason {
        NOT_GRANTED,
        REVOKED,
        OUTDATED_VERSION
    }
}
