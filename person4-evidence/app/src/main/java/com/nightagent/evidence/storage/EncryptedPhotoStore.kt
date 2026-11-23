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
 * Photo-specific entry point for encrypted publication.
 *
 * All capture-to-encryption safety rules live in [EncryptedEvidenceStore]; this
 * wrapper only selects the photo directory, media type and filename convention.
 */
class EncryptedPhotoStore(
    filesDir: File,
    encryptor: EvidenceEncryptor,
    metadataRepository: EvidenceMetadataRepository,
    newEncryptedName: () -> String = { EncryptedPhotoFileName.newName() },
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
        encryptedDirectory = EncryptedPhotoFileName.ENCRYPTED_PHOTOS_DIRECTORY,
        mediaType = EvidenceType.PHOTO,
        newEncryptedName = newEncryptedName,
        deletePlaintext = deletePlaintext,
        metadataFactory = metadataFactory,
        ioDispatcher = ioDispatcher,
        logger = logger
    )

    suspend fun encryptAndPublish(plaintext: File): EncryptedEvidenceOutcome =
        delegate.encryptAndPublish(plaintext)
}
