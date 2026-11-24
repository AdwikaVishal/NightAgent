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
 * Audio-specific entry point for encrypted publication.
 *
 * The M4A source is only encrypted after MediaRecorder has finalized and the
 * file has been validated. All plaintext-safety and metadata rules are shared
 * with photo and video publication through [EncryptedEvidenceStore].
 */
class EncryptedAudioStore(
    filesDir: File,
    encryptor: EvidenceEncryptor,
    metadataRepository: EvidenceMetadataRepository,
    newEncryptedName: () -> String = { EncryptedAudioFileName.newName() },
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
        encryptedDirectory = EncryptedAudioFileName.ENCRYPTED_AUDIO_DIRECTORY,
        mediaType = EvidenceType.AUDIO,
        newEncryptedName = newEncryptedName,
        deletePlaintext = deletePlaintext,
        metadataFactory = metadataFactory,
        ioDispatcher = ioDispatcher,
        logger = logger
    )

    suspend fun encryptAndPublish(plaintext: File): EncryptedEvidenceOutcome =
        delegate.encryptAndPublish(plaintext)
}
