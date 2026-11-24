package com.nightagent.evidence.consent

/**
 * Version of the consent wording presented to the user.
 *
 * Increment this when the terms change materially. Records stored with an
 * older version stop being valid immediately, so users must consent again.
 */
object ConsentTerms {
    const val CURRENT_VERSION: Int = 1
}
