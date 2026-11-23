package com.nightagent.evidence.sync

import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.integration.AuthorizationResult
import com.nightagent.evidence.integration.EvidenceAuthorization
import com.nightagent.evidence.metadata.EncryptedContainerPath
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Reusable cloud-upload entry point.
 *
 * Consent is checked on every call, at the moment of upload — it is never
 * carried over from capture time. Only a verified, published `.ng1` container
 * is handed to the uploader. With no configured backend the result is
 * [UploadResult.NotConfigured]; a successful upload is never simulated.
 *
 * Retry by calling [upload] again; cancellation is the caller cancelling the
 * coroutine, which is reported as [UploadResult.Cancelled].
 */
class CloudUploadController(
    private val authorization: EvidenceAuthorization,
    private val filesDir: File,
    private val metadataRepository: EvidenceMetadataRepository,
    private val integrityVerifier: EvidenceIntegrityVerifier,
    private val uploader: EvidenceUploader
) {

    private val mutableState = MutableStateFlow(UploadState.IDLE)
    val state: StateFlow<UploadState> = mutableState.asStateFlow()

    fun reset() {
        mutableState.value = UploadState.IDLE
    }

    suspend fun upload(evidenceId: String): UploadResult {
        mutableState.value = UploadState.AUTHORIZING
        val authorizationResult = try {
            authorization.authorize(EvidenceOperation.CLOUD_UPLOAD)
        } catch (e: CancellationException) {
            mutableState.value = UploadState.CANCELLED
            throw e
        } catch (e: Exception) {
            mutableState.value = UploadState.FAILED
            return UploadResult.Failed(UploadStage.AUTHORIZATION, e)
        }
        if (authorizationResult is AuthorizationResult.Denied) {
            mutableState.value = UploadState.DENIED
            return UploadResult.Denied(authorizationResult.reason)
        }

        mutableState.value = UploadState.PREPARING
        val metadata = try {
            metadataRepository.findById(evidenceId)
        } catch (e: CancellationException) {
            mutableState.value = UploadState.CANCELLED
            throw e
        } catch (e: Exception) {
            mutableState.value = UploadState.FAILED
            return UploadResult.Failed(UploadStage.METADATA, e)
        } ?: run {
            mutableState.value = UploadState.FAILED
            return UploadResult.NotFound
        }

        val verification = integrityVerifier.verify(evidenceId)
        if (verification.status != IntegrityStatus.VERIFIED) {
            mutableState.value = UploadState.FAILED
            return UploadResult.IntegrityFailed(verification.status)
        }

        val container = EncryptedContainerPath.resolve(filesDir, metadata.containerRelativePath)
        if (container == null) {
            mutableState.value = UploadState.FAILED
            return UploadResult.Failed(UploadStage.CONTAINER_PATH)
        }

        if (!uploader.isConfigured) {
            mutableState.value = UploadState.NOT_CONFIGURED
            return UploadResult.NotConfigured(NO_BACKEND_REASON)
        }

        val request = EncryptedUploadRequest(
            evidenceId = evidenceId,
            container = container,
            containerSizeBytes = metadata.containerSizeBytes,
            sha256 = metadata.sha256,
            mediaType = metadata.mediaType
        )

        mutableState.value = UploadState.UPLOADING
        val result = try {
            uploader.upload(request)
        } catch (e: CancellationException) {
            mutableState.value = UploadState.CANCELLED
            return UploadResult.Cancelled
        } catch (e: Exception) {
            mutableState.value = UploadState.FAILED
            return UploadResult.Failed(UploadStage.UPLOAD, e)
        }

        mutableState.value = when (result) {
            is UploadResult.Uploaded -> UploadState.SUCCESS
            is UploadResult.Denied -> UploadState.DENIED
            is UploadResult.NotConfigured -> UploadState.NOT_CONFIGURED
            is UploadResult.IntegrityFailed -> UploadState.FAILED
            UploadResult.NotFound -> UploadState.FAILED
            is UploadResult.Failed -> UploadState.FAILED
            UploadResult.Cancelled -> UploadState.CANCELLED
        }
        return result
    }

    companion object {
        const val NO_BACKEND_REASON: String =
            "No cloud upload backend is configured for this application id; no upload was attempted"
    }
}
