package com.nightagent.evidence.vault

import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.integrity.IntegrityVerificationResult
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one on-demand verification request. */
sealed interface VerificationAttempt {
    data class Completed(
        val result: IntegrityVerificationResult,
        val metadataBefore: EvidenceMetadata?,
        val metadataAfter: EvidenceMetadata?
    ) : VerificationAttempt

    /** A second request arrived while the first was still running. */
    data object Duplicate : VerificationAttempt
}

/**
 * UI-facing verification controller. It reloads current metadata before and
 * after the read-only verifier runs so the screen can show the latest record
 * and confirm that verification did not modify stored evidence data.
 */
class EvidenceDetailsController(
    private val repository: EvidenceMetadataRepository,
    private val verifier: EvidenceIntegrityVerifier,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val verifying = AtomicBoolean(false)

    suspend fun loadLatest(evidenceId: String): EvidenceMetadata? = withContext(ioDispatcher) {
        repository.findById(evidenceId)
    }

    suspend fun verifyOnce(evidenceId: String): VerificationAttempt {
        if (!verifying.compareAndSet(false, true)) return VerificationAttempt.Duplicate

        return try {
            withContext(ioDispatcher) {
                val before = try {
                    repository.findById(evidenceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext VerificationAttempt.Completed(
                        result = IntegrityVerificationResult(
                            evidenceId = evidenceId,
                            status = IntegrityStatus.VERIFICATION_ERROR,
                            detail = "Metadata lookup failed"
                        ),
                        metadataBefore = null,
                        metadataAfter = null
                    )
                }

                if (before == null) {
                    return@withContext VerificationAttempt.Completed(
                        result = IntegrityVerificationResult(
                            evidenceId = evidenceId,
                            status = IntegrityStatus.METADATA_NOT_FOUND
                        ),
                        metadataBefore = null,
                        metadataAfter = null
                    )
                }

                val result = verifier.verify(evidenceId)
                val after = try {
                    repository.findById(evidenceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }

                VerificationAttempt.Completed(
                    result = result,
                    metadataBefore = before,
                    metadataAfter = after
                )
            }
        } finally {
            verifying.set(false)
        }
    }
}
