package com.example.nightagent.guardian.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardian delivery must never be reported as successful unless the trusted
 * Cloud Function explicitly confirms at least one recipient.
 */
class GuardianNotificationResponseTest {

    @Test
    fun malformedOrMissingResponse_isRejected() {
        assertTrue(GuardianNotificationResponse.parse(null).isFailure)
        assertTrue(GuardianNotificationResponse.parse("ok").isFailure)
    }

    @Test
    fun queuedOrRequestedStatus_isRejected() {
        assertTrue(
            GuardianNotificationResponse.parse(
                mapOf("status" to "emergency_notification_requested", "deliveredGuardianIds" to listOf("g1"))
            ).isFailure
        )
        assertTrue(
            GuardianNotificationResponse.parse(
                mapOf("status" to "not_delivered", "deliveredGuardianIds" to emptyList<String>())
            ).isFailure
        )
    }

    @Test
    fun deliveredWithNoConfirmedRecipients_isRejected() {
        val result = GuardianNotificationResponse.parse(
            mapOf("status" to "delivered", "deliveredGuardianIds" to emptyList<String>())
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun deliveredWithConfirmedRecipients_returnsNormalizedIds() {
        val result = GuardianNotificationResponse.parse(
            mapOf(
                "status" to "delivered",
                "deliveredGuardianIds" to listOf("g1", " g2 ", "g1", "", 42)
            )
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("g1", "g2"), result.getOrThrow())
    }
}
