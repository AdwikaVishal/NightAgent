package com.nightagent.evidence.retention

/** Stage at which an explicit delete request stopped. */
enum class DeletionStage {
    METADATA_LOOKUP,
    CONTAINER_PATH,
    CONTAINER_DELETE,
    METADATA_DELETE
}

/**
 * Explicit deletion outcomes. Partial failures are reported rather than
 * silently treated as success; no automatic expiry is part of this module.
 */
sealed interface DeletionResult {
    data object Deleted : DeletionResult
    data object NotFound : DeletionResult

    data class PartialFailure(
        val containerDeleted: Boolean,
        val metadataDeleted: Boolean,
        val cause: Throwable? = null
    ) : DeletionResult

    data class Failed(
        val stage: DeletionStage,
        val cause: Throwable? = null
    ) : DeletionResult
}
