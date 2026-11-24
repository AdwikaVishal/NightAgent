package com.nightagent.evidence.integration

import com.nightagent.evidence.access.EvidenceAccessController
import com.nightagent.evidence.access.EvidenceAccessResult
import com.nightagent.evidence.consent.ConsentRepository
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityVerificationResult
import com.nightagent.evidence.metadata.EvidenceListResult
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.retention.DeletionResult
import com.nightagent.evidence.retention.EvidenceRetentionController
import com.nightagent.evidence.sync.CloudUploadController
import com.nightagent.evidence.sync.UploadResult
import com.nightagent.evidence.sync.UploadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Public, Compose-free entry point for a host application.
 *
 * Consent enforcement lives inside this module: capture triggers and media
 * publication are authorized here, and cloud upload re-checks consent at upload
 * time in [CloudUploadController]. The host only supplies Android-specific
 * dependencies and never has to repeat a security check itself.
 */
class EvidenceModule(
    val authorization: EvidenceAuthorization,
    val consentRepository: ConsentRepository,
    val metadataRepository: EvidenceMetadataRepository,
    val integrityVerifier: EvidenceIntegrityVerifier,
    val accessController: EvidenceAccessController,
    val retentionController: EvidenceRetentionController,
    val cloudUploadController: CloudUploadController,
    val publicationController: EvidencePublicationController,
    val captureTrigger: EvidenceCaptureTrigger
) {

    fun observeConsent(): Flow<Map<ConsentKey, ConsentRecord>> =
        consentRepository.observeAll()

    fun observeConsent(key: ConsentKey): Flow<ConsentRecord> =
        consentRepository.observe(key)

    suspend fun consentRecord(key: ConsentKey): ConsentRecord =
        consentRepository.get(key)

    suspend fun grantConsent(key: ConsentKey) = consentRepository.grant(key)

    suspend fun revokeConsent(key: ConsentKey) = consentRepository.revoke(key)

    /** Call before presenting any capture UI. */
    suspend fun authorizeCapture(type: EvidenceType): AuthorizationResult =
        authorization.authorize(EvidenceOperation.forCapture(type))

    suspend fun isAuthorized(operation: EvidenceOperation): Boolean =
        authorization.isAuthorized(operation)

    /** Authorized SOS/host trigger; never bypasses consent or permissions. */
    suspend fun triggerCapture(request: CaptureTriggerRequest): CaptureTriggerResult =
        when (val result = authorizeCapture(request.type)) {
            AuthorizationResult.Authorized -> captureTrigger.trigger(request)
            is AuthorizationResult.Denied -> CaptureTriggerResult.Denied(result.reason)
        }

    /**
     * Publishes media the host already captured in its own foreground UI.
     * Consent and Android permissions are re-checked here; the stable evidence
     * id is returned only after NGE1 verification, SHA-256 and metadata
     * registration all succeeded.
     */
    suspend fun publish(plaintext: File, type: EvidenceType): PublicationResult =
        when (val result = authorizeCapture(type)) {
            AuthorizationResult.Authorized -> publicationController.publish(plaintext, type)
            is AuthorizationResult.Denied -> PublicationResult.Denied(result.reason)
        }

    fun observeEvidence(): Flow<EvidenceListResult> = metadataRepository.observeAll()

    fun observeEvidence(type: EvidenceType): Flow<EvidenceListResult> =
        metadataRepository.observeByMediaType(type)

    suspend fun evidenceDetails(evidenceId: String): EvidenceMetadata? =
        metadataRepository.findById(evidenceId)

    suspend fun verify(evidenceId: String): IntegrityVerificationResult =
        integrityVerifier.verify(evidenceId)

    suspend fun open(evidenceId: String): EvidenceAccessResult =
        accessController.open(evidenceId)

    /** Number of temporary plaintext sessions currently outstanding. */
    fun activeAccessTokenCount(): Int = accessController.activeTokenCount()

    /** Observable upload progress for host UI; no plaintext is ever exposed. */
    val uploadState: StateFlow<UploadState> get() = cloudUploadController.state

    fun resetUploadState() = cloudUploadController.reset()

    suspend fun upload(evidenceId: String): UploadResult =
        cloudUploadController.upload(evidenceId)

    suspend fun delete(evidenceId: String): DeletionResult =
        retentionController.delete(evidenceId)

    /** Call from host lifecycle teardown to remove all temporary plaintext. */
    fun releaseEvidenceAccess() = accessController.releaseAll()
}
