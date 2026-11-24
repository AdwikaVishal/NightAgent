package com.nightagent.evidence.storage

import com.nightagent.evidence.crypto.EvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.SilentEvidenceDiagnosticLogger
import com.nightagent.evidence.metadata.EvidenceMetadataFactory
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.metadata.MetadataCreationResult
import com.nightagent.evidence.model.EvidenceType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Video-specific entry point for encrypted publication.
 *
 * The MP4 source is only encrypted after CameraX reports successful finalization
 * and the file passes validation. All plaintext-safety and metadata rules are
 * shared with photo and audio publication through [EncryptedEvidenceStore].
 */
class EncryptedVideoStore(
    filesDir: File,
    encryptor: EvidenceEncryptor,
    metadataRepository: EvidenceMetadataRepository,
    newEncryptedName: () -> String = { EncryptedVideoFileName.newName() },
    deletePlaintext: (File) -> Boolean = { it.delete() },
    metadataFactory: (String) -> MetadataCreationResult = { relativePath ->
        EvidenceMetadataFactory.fromPublishedContainer(filesDir, relativePath)
    },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    logger: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) {

    private val delegate = EncryptedEvidenceStore(
        filesDir = filesDir,
        encryptor = encryptor,
        metadataRepository = metadataRepository,
        encryptedDirectory = EncryptedVideoFileName.ENCRYPTED_VIDEOS_DIRECTORY,
        mediaType = EvidenceType.VIDEO,
        newEncryptedName = newEncryptedName,
        deletePlaintext = deletePlaintext,
        metadataFactory = metadataFactory,
        ioDispatcher = ioDispatcher,
        logger = logger
    )

    suspend fun encryptAndPublish(plaintext: File): EncryptedEvidenceOutcome =
        delegate.encryptAndPublish(plaintext)
}
