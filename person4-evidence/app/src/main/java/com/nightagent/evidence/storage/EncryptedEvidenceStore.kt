package com.nightagent.evidence.storage

import com.nightagent.evidence.metadata.EvidenceMetadataFactory
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.crypto.EvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.EvidenceFailureRecorder
import com.nightagent.evidence.crypto.SilentEvidenceDiagnosticLogger
import com.nightagent.evidence.metadata.MetadataCreationResult
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.MetadataStatusUpdateResult
import com.nightagent.evidence.model.EvidenceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Why a captured source file could not be published as encrypted evidence. */
enum class EncryptedEvidenceFailure {
    STORAGE_PREPARATION,
    CAPTURE_FILE_INVALID,
    ENCRYPTION_FAILED,
    VERIFICATION_FAILED
}

/** Why published encrypted evidence is still awaiting local metadata. */
enum class MetadataOutcomeFailure {
    DIGEST_FAILED,
    REGISTRATION_FAILED,
    CONFLICTING_RECORD
}

/** Outcome of a capture-to-encryption pipeline. No plaintext paths are exposed. */
sealed interface EncryptedEvidenceOutcome {
    data class Success(
        val encryptedName: String,
        val cleanupPending: Boolean,
        val cleanupStatusPending: Boolean = false
    ) : EncryptedEvidenceOutcome

    /**
     * The NGE1 container was published and verified, but metadata is not safely
     * registered. Both the encrypted container and the plaintext are preserved.
     */
    data class MetadataPending(
        val encryptedName: String,
        val reason: MetadataOutcomeFailure,
        val diagnostic: EvidenceFailureDiagnostic? = null
    ) : EncryptedEvidenceOutcome

    data class Failure(
        val reason: EncryptedEvidenceFailure,
        val diagnostic: EvidenceFailureDiagnostic? = null
    ) : EncryptedEvidenceOutcome
}

/**
 * Shared NGE1 publication and metadata coordinator.
 *
 * The plaintext source is never deleted before encryption, post-publication
 * verification, SHA-256 derivation and metadata registration all succeed. All
 * file and database work runs on [ioDispatcher], never on the Android main
 * thread. Cryptographic behavior remains entirely in the existing engine.
 *
 * When the injected [logger] is an [EvidenceFailureRecorder], each non-success
 * outcome also carries a sanitized [EvidenceFailureDiagnostic] so the exact
 * failing stage can be read in-app without ADB.
 */
class EncryptedEvidenceStore(
    private val filesDir: File,
    private val encryptor: EvidenceEncryptor,
    private val metadataRepository: EvidenceMetadataRepository,
    private val encryptedDirectory: String,
    private val mediaType: EvidenceType,
    private val newEncryptedName: () -> String,
    private val deletePlaintext: (File) -> Boolean = { it.delete() },
    private val metadataFactory: (String) -> MetadataCreationResult = { relativePath ->
        EvidenceMetadataFactory.fromPublishedContainer(filesDir, relativePath)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) {

    suspend fun encryptAndPublish(plaintext: File): EncryptedEvidenceOutcome =
        withContext(ioDispatcher) {
            val recorder = logger as? EvidenceFailureRecorder
            val operation = mediaType.name
            recorder?.beginOperation(operation)
            logger.stage(operation, "STORE_START")
            if (!plaintext.isFile || plaintext.length() <= 0L) {
                return@withContext EncryptedEvidenceOutcome.Failure(
                    EncryptedEvidenceFailure.CAPTURE_FILE_INVALID,
                    diagnosticFor(recorder, operation, "STORE_INPUT_VALIDATION", null, false, false)
                )
            }

            val directory = File(filesDir, encryptedDirectory)
            if (!ensureDirectory(directory)) {
                return@withContext EncryptedEvidenceOutcome.Failure(
                    EncryptedEvidenceFailure.STORAGE_PREPARATION,
                    diagnosticFor(recorder, operation, "STORE_STORAGE_PREPARATION", null, false, false)
                )
            }

            val target = File(directory, newEncryptedName())
            if (target.exists()) {
                return@withContext EncryptedEvidenceOutcome.Failure(
                    EncryptedEvidenceFailure.STORAGE_PREPARATION,
                    diagnosticFor(recorder, operation, "STORE_TARGET_EXISTS", null, false, false)
                )
            }

            try {
                encryptor.encrypt(plaintext, target, mediaType)
            } catch (e: Exception) {
                logger.failure(operation, "STORE_ENCRYPT", e)
                // Any target that appeared during a failed encrypt is unverified and
                // must not remain as evidence. The plaintext is deliberately kept.
                val targetPresent = target.exists()
                deleteQuietly(target)
                return@withContext EncryptedEvidenceOutcome.Failure(
                    EncryptedEvidenceFailure.ENCRYPTION_FAILED,
                    diagnosticFor(recorder, operation, "STORE_ENCRYPT", e, targetPresent, false)
                )
            }

            try {
                encryptor.verify(target)
            } catch (e: Exception) {
                logger.failure(operation, "STORE_VERIFY", e)
                // Explicit post-publication verification failed: remove the invalid
                // published copy and keep the original plaintext for retry/recovery.
                val targetPresent = target.exists()
                deleteQuietly(target)
                return@withContext EncryptedEvidenceOutcome.Failure(
                    EncryptedEvidenceFailure.VERIFICATION_FAILED,
                    diagnosticFor(recorder, operation, "STORE_VERIFY", e, targetPresent, false)
                )
            }

            val relativePath = target.relativeTo(filesDir).path.replace(File.separatorChar, '/')
            val creation = try {
                metadataFactory(relativePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.failure(operation, "STORE_METADATA_DIGEST", e)
                return@withContext EncryptedEvidenceOutcome.MetadataPending(
                    encryptedName = target.name,
                    reason = MetadataOutcomeFailure.DIGEST_FAILED,
                    diagnostic = diagnosticFor(recorder, operation, "STORE_METADATA_DIGEST", e, true, false)
                )
            }

            // EvidenceMetadataFactory streams SHA-256 over the complete published
            // container, reads the NGE1 header and derives the stable file ID.
            val metadata = when (creation) {
                is MetadataCreationResult.Created -> creation.metadata
                else -> return@withContext EncryptedEvidenceOutcome.MetadataPending(
                    encryptedName = target.name,
                    reason = MetadataOutcomeFailure.DIGEST_FAILED,
                    diagnostic = diagnosticFor(recorder, operation, "STORE_METADATA_DIGEST", null, true, false)
                )
            }

            val registration = try {
                metadataRepository.register(metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.failure(operation, "STORE_METADATA_REGISTER", e)
                return@withContext EncryptedEvidenceOutcome.MetadataPending(
                    encryptedName = target.name,
                    reason = MetadataOutcomeFailure.REGISTRATION_FAILED,
                    diagnostic = diagnosticFor(recorder, operation, "STORE_METADATA_REGISTER", e, true, true)
                )
            }

            when (registration) {
                MetadataRegistrationResult.Registered,
                MetadataRegistrationResult.AlreadyRegistered -> Unit

                MetadataRegistrationResult.ConflictingRecord ->
                    return@withContext EncryptedEvidenceOutcome.MetadataPending(
                        encryptedName = target.name,
                        reason = MetadataOutcomeFailure.CONFLICTING_RECORD,
                        diagnostic = diagnosticFor(recorder, operation, "STORE_METADATA_CONFLICT", null, true, true)
                    )

                is MetadataRegistrationResult.Failed ->
                    return@withContext EncryptedEvidenceOutcome.MetadataPending(
                        encryptedName = target.name,
                        reason = MetadataOutcomeFailure.REGISTRATION_FAILED,
                        diagnostic = diagnosticFor(recorder, operation, "STORE_METADATA_REGISTER", null, true, true)
                    )
            }

            val cleanupPending = try {
                !deletePlaintext(plaintext)
            } catch (e: Exception) {
                true
            }

            val cleanupStatusPending = if (cleanupPending) {
                val statusUpdate = try {
                    metadataRepository.markCleanupPending(metadata)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.failure(operation, "STORE_CLEANUP_STATUS", e)
                    MetadataStatusUpdateResult.Failed(e)
                }
                statusUpdate !is MetadataStatusUpdateResult.Updated &&
                    statusUpdate !is MetadataStatusUpdateResult.AlreadyPending
            } else {
                false
            }

            logger.stage(operation, "STORE_SUCCESS")
            EncryptedEvidenceOutcome.Success(
                encryptedName = target.name,
                cleanupPending = cleanupPending,
                cleanupStatusPending = cleanupStatusPending
            )
        }

    /**
     * Builds a sanitized diagnostic from the recorder's first failure for this
     * operation. Returns null when no recorder is installed (e.g. JVM tests that
     * use the silent logger), preserving the original outcome shape.
     */
    private fun diagnosticFor(
        recorder: EvidenceFailureRecorder?,
        operation: String,
        fallbackStage: String,
        error: Throwable?,
        targetPublished: Boolean,
        metadataRegistrationAttempted: Boolean
    ): EvidenceFailureDiagnostic? {
        if (recorder == null) return null
        val recorded = recorder.recordedFailure(operation)
        return EvidenceFailureDiagnostic(
            operation = operation,
            stage = recorded?.stage ?: fallbackStage,
            exceptionClass = recorded?.exceptionClass
                ?: error?.let { it::class.java.simpleName }
                ?: "None",
            errorCode = recorded?.errorCode,
            targetPublished = targetPublished,
            metadataRegistrationAttempted = metadataRegistrationAttempted
        )
    }

    private fun ensureDirectory(directory: File): Boolean = try {
        if (!directory.exists() && !directory.mkdirs()) {
            false
        } else {
            directory.isDirectory && directory.canWrite()
        }
    } catch (e: Exception) {
        false
    }

    private fun deleteQuietly(file: File) {
        runCatching {
            if (file.exists() && !file.delete()) file.deleteOnExit()
        }
    }
}
