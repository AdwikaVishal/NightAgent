package com.example.nightagent.guardian.repository

/**
 * Parses the trusted Cloud Function response for guardian delivery.
 *
 * A response is only a success when the backend explicitly confirms at least
 * one recipient. Logging-only or queued requests must never be reported as
 * delivered.
 */
internal object GuardianNotificationResponse {
    fun parse(response: Any?): Result<List<String>> {
        val data = response as? Map<*, *>
            ?: return Result.failure(IllegalStateException("Empty guardian notification response"))
        val status = data["status"] as? String
        if (status != "delivered") {
            return Result.failure(IllegalStateException("Guardian delivery not confirmed (status: ${status ?: "unknown"})"))
        }
        val delivered = (data["deliveredGuardianIds"] as? List<*>)
            ?.filterIsInstance<String>()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        if (delivered.isEmpty()) {
            return Result.failure(IllegalStateException("No guardian delivery was confirmed by the backend"))
        }
        return Result.success(delivered)
    }
}
