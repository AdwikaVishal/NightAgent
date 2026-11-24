package com.nightagent.evidence.integration

import android.content.Context
import com.nightagent.evidence.access.EvidenceAccessController
import com.nightagent.evidence.consent.ConsentGate
import com.nightagent.evidence.consent.PersistentConsentRepository
import com.nightagent.evidence.crypto.AndroidAtomicFilePublisher
import com.nightagent.evidence.crypto.AndroidEvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.AndroidEvidenceKeyManager
import com.nightagent.evidence.crypto.EvidenceDiagnosticLogger
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.RecordingEvidenceDiagnosticLogger
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.metadata.EvidenceMetadataDatabase
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.permission.AndroidPermissionStateProvider
import com.nightagent.evidence.retention.EvidenceRetentionController
import com.nightagent.evidence.sync.CloudUploadController
import com.nightagent.evidence.sync.EvidenceUploader
import com.nightagent.evidence.sync.NotConfiguredEvidenceUploader

/**
 * Recommended assembly point for a host application.
 *
 * It wires the existing Keystore engine, Room metadata repository and consent
 * system once, then exposes only [EvidenceModule]. No Compose class, Activity
 * or harness screen is required by the host. Capture triggering and cloud
 * upload remain explicit host-supplied boundaries with safe defaults.
 */
object EvidenceModuleFactory {

    fun create(
        context: Context,
        captureTrigger: EvidenceCaptureTrigger = MockEvidenceCaptureTrigger(),
        uploader: EvidenceUploader = NotConfiguredEvidenceUploader(),
        logger: EvidenceDiagnosticLogger = RecordingEvidenceDiagnosticLogger(
            AndroidEvidenceDiagnosticLogger()
        )
    ): EvidenceModule {
        val appContext = context.applicationContext
        val consentRepository = PersistentConsentRepository.create(appContext)
        val authorization = EvidenceAuthorization(
            ConsentGate(consentRepository),
            AndroidPermissionStateProvider(appContext)
        )
        val metadataRepository = RoomEvidenceMetadataRepository(
            EvidenceMetadataDatabase.getInstance(appContext).metadataDao()
        )
        val engine = EvidenceEncryptionEngine(
            keyAccess = AndroidEvidenceKeyManager(logger = logger),
            publisher = AndroidAtomicFilePublisher(logger = logger),
            logger = logger
        )
        val filesDir = appContext.filesDir
        val verifier = EvidenceIntegrityVerifier.withEngine(filesDir, metadataRepository, engine)
        val accessController = EvidenceAccessController(filesDir, metadataRepository, verifier, engine)
        val retentionController = EvidenceRetentionController(filesDir, metadataRepository)
        val uploadController = CloudUploadController(
            authorization = authorization,
            filesDir = filesDir,
            metadataRepository = metadataRepository,
            integrityVerifier = verifier,
            uploader = uploader
        )
        val publicationController = EvidencePublicationController(
            filesDir = filesDir,
            metadataRepository = metadataRepository,
            engine = engine,
            logger = logger
        )
        return EvidenceModule(
            authorization = authorization,
            consentRepository = consentRepository,
            metadataRepository = metadataRepository,
            integrityVerifier = verifier,
            accessController = accessController,
            retentionController = retentionController,
            cloudUploadController = uploadController,
            publicationController = publicationController,
            captureTrigger = captureTrigger
        )
    }
}
