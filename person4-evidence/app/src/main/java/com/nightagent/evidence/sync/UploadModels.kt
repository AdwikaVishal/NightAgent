package com.nightagent.evidence.sync

import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.integration.AuthorizationResult
import com.nightagent.evidence.model.EvidenceType
import java.io.File

/** Observable state of one cloud-upload request. */
enum class UploadState {
    IDLE,
    AUTHORIZING,
    PREPARING,
    UPLOADING,
    SUCCESS,
    FAILED,
    CANCELLED,
    DENIED,
    NOT_CONFIGURED
}

/** Stage at which an upload request stopped. */
enum class UploadStage {
    AUTHORIZATION,
    METADATA,
    INTEGRITY,
    CONTAINER_PATH,
    UPLOAD
}

/**
 * The only payload an uploader ever receives: an already published `.ng1`
 * container plus its recorded size and digest. No plaintext path, no data key,
 * no master key and no metadata record are passed to the uploader.
 */
class EncryptedUploadRequest(
    val evidenceId: String,
    val container: File,
    val containerSizeBytes: Long,
    val sha256: String,
    val mediaType: EvidenceType
) {
    init {
        require(container.name.endsWith(".ng1", ignoreCase = true)) {
            "Only published .ng1 containers may be uploaded"
        }
        require(sha256.length == 64) { "SHA-256 digest must be hex encoded" }
    }

    override fun toString(): String =
        "EncryptedUploadRequest(evidenceId=$evidenceId, mediaType=$mediaType, bytes=$containerSizeBytes)"
}

/** Explicit upload outcomes; a not-configured backend never reports success. */
sealed interface UploadResult {
    data class Uploaded(val evidenceId: String, val remoteReference: String? = null) : UploadResult
    data class Denied(val reason: AuthorizationResult.Reason) : UploadResult
    data class NotConfigured(val reason: String) : UploadResult
    data class IntegrityFailed(val status: IntegrityStatus) : UploadResult
    data object NotFound : UploadResult
    data class Failed(val stage: UploadStage, val cause: Throwable? = null) : UploadResult
    data object Cancelled : UploadResult
}
