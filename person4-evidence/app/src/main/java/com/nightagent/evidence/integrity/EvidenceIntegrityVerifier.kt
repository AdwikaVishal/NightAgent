package com.nightagent.evidence.integrity

import com.nightagent.evidence.crypto.ContainerFormatException
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.metadata.EncryptedContainerPath
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Local consistency check for published encrypted evidence.
 *
 * A locally stored hash and its database row can both be modified by an
 * attacker with private-storage access. This component provides a useful local
 * check only; it is not an independently trusted chain-of-custody. It never
 * repairs, deletes, overwrites, or updates stored metadata.
 */
class EvidenceIntegrityVerifier(
    private val filesDir: File,
    private val metadataRepository: EvidenceMetadataRepository,
    private val nge1Verifier: Nge1ContainerVerifier,
    private val digests: ContainerDigests = ContainerDigests
) {

    suspend fun verify(evidenceId: String): IntegrityVerificationResult {
        if (evidenceId.isBlank()) return result(evidenceId, IntegrityStatus.METADATA_NOT_FOUND)

        val metadata = try {
            metadataRepository.findById(evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return result(evidenceId, IntegrityStatus.VERIFICATION_ERROR, "Metadata lookup failed")
        } ?: return result(evidenceId, IntegrityStatus.METADATA_NOT_FOUND)

        val container = EncryptedContainerPath.resolve(filesDir, metadata.containerRelativePath)
            ?: return result(evidenceId, IntegrityStatus.VERIFICATION_ERROR, "Invalid container path")

        if (!container.exists() || !container.isFile) {
            return result(evidenceId, IntegrityStatus.FILE_MISSING)
        }

        val actualSize = try {
            container.length()
        } catch (e: SecurityException) {
            return result(evidenceId, IntegrityStatus.VERIFICATION_ERROR, "Container size unavailable")
        }
        if (actualSize != metadata.containerSizeBytes) {
            return result(evidenceId, IntegrityStatus.SIZE_MISMATCH)
        }

        val actualDigest = when (val digest = digests.ofFile(container)) {
            is ContainerDigestResult.Hashed -> digest.hex
            ContainerDigestResult.Missing -> {
                return result(evidenceId, IntegrityStatus.FILE_MISSING)
            }
            ContainerDigestResult.Unreadable -> {
                return result(evidenceId, IntegrityStatus.VERIFICATION_ERROR, "Container unreadable")
            }
        }
        if (!actualDigest.equals(metadata.sha256, ignoreCase = true)) {
            return result(evidenceId, IntegrityStatus.HASH_MISMATCH)
        }

        return try {
            nge1Verifier.verify(container)
            result(evidenceId, IntegrityStatus.VERIFIED)
        } catch (e: ContainerFormatException) {
            result(evidenceId, IntegrityStatus.NGE1_AUTHENTICATION_FAILED, "NGE1 verification failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result(evidenceId, IntegrityStatus.VERIFICATION_ERROR, "NGE1 verification unavailable")
        }
    }

    companion object {
        fun withEngine(
            filesDir: File,
            metadataRepository: EvidenceMetadataRepository,
            engine: EvidenceEncryptionEngine
        ): EvidenceIntegrityVerifier = EvidenceIntegrityVerifier(
            filesDir = filesDir,
            metadataRepository = metadataRepository,
            nge1Verifier = EngineNge1ContainerVerifier(engine)
        )

        private fun result(
            evidenceId: String,
            status: IntegrityStatus,
            detail: String? = null
        ) = IntegrityVerificationResult(
            evidenceId = evidenceId,
            status = status,
            detail = detail
        )
    }
}
