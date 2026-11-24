package com.nightagent.evidence.integration

import com.nightagent.evidence.crypto.EvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.SilentEvidenceDiagnosticLogger
import com.nightagent.evidence.metadata.EvidenceListResult
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.storage.EncryptedAudioStore
import com.nightagent.evidence.storage.EncryptedEvidenceOutcome
import com.nightagent.evidence.storage.EncryptedPhotoStore
import com.nightagent.evidence.storage.EncryptedVideoStore
import com.nightagent.evidence.storage.Nge1EvidenceEncryptor
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Host-facing publication boundary for media captured by the host app.
 *
 * It delegates to the exact same encrypted stores used by the harness, so the
 * plaintext-preservation, NGE1 verification, SHA-256 and metadata rules are
 * unchanged. Its only added value is mapping the store's outcome to a stable
 * evidence id the host can use for verification, access, upload and deletion.
 *
 * Authorization is enforced by [EvidenceModule] before this controller runs;
 * this class never grants consent or bypasses permissions itself.
 */
class EvidencePublicationController(
    private val filesDir: File,
    private val metadataRepository: EvidenceMetadataRepository,
    engine: EvidenceEncryptionEngine,
    logger: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) {
    private val encryptor = Nge1EvidenceEncryptor(engine)

    private val photoStore = EncryptedPhotoStore(
        filesDir = filesDir,
        encryptor = encryptor,
        metadataRepository = metadataRepository,
        logger = logger
    )
    private val audioStore = EncryptedAudioStore(
        filesDir = filesDir,
        encryptor = encryptor,
        metadataRepository = metadataRepository,
        logger = logger
    )
    private val videoStore = EncryptedVideoStore(
        filesDir = filesDir,
        encryptor = encryptor,
        metadataRepository = metadataRepository,
        logger = logger
    )

    suspend fun publish(plaintext: File, type: EvidenceType): PublicationResult {
        val outcome = when (type) {
            EvidenceType.PHOTO -> photoStore.encryptAndPublish(plaintext)
            EvidenceType.AUDIO -> audioStore.encryptAndPublish(plaintext)
            EvidenceType.VIDEO -> videoStore.encryptAndPublish(plaintext)
        }

        return when (outcome) {
            is EncryptedEvidenceOutcome.Success -> {
                val evidenceId = findEvidenceId(outcome.encryptedName)
                if (evidenceId == null) {
                    PublicationResult.MetadataLookupFailed(outcome.encryptedName)
                } else {
                    PublicationResult.Published(
                        evidenceId = evidenceId,
                        encryptedName = outcome.encryptedName,
                        cleanupPending = outcome.cleanupPending,
                        cleanupStatusPending = outcome.cleanupStatusPending
                    )
                }
            }

            is EncryptedEvidenceOutcome.MetadataPending -> PublicationResult.PendingMetadata(
                encryptedName = outcome.encryptedName,
                reason = outcome.reason
            )

            is EncryptedEvidenceOutcome.Failure -> PublicationResult.Failed(outcome.reason)
        }
    }

    /** Reads back via the repository; no new database column or schema change. */
    private suspend fun findEvidenceId(encryptedName: String): String? {
        val records = when (val list = metadataRepository.observeAll().first()) {
            is EvidenceListResult.Success -> list.records
            is EvidenceListResult.Error -> return null
        }
        return records.firstOrNull {
            it.containerRelativePath.substringAfterLast('/') == encryptedName
        }?.evidenceId
    }
}
