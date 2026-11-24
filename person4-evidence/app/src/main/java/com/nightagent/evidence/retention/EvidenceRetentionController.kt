package com.nightagent.evidence.retention

import com.nightagent.evidence.metadata.EncryptedContainerPath
import com.nightagent.evidence.metadata.EvidenceListResult
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import com.nightagent.evidence.metadata.MetadataDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Minimal explicit deletion operation for registered encrypted evidence.
 *
 * Safety rules enforced here:
 *  - the metadata row must exist and the path must resolve to an app-private
 *    `.ng1` container;
 *  - a container referenced by another evidence row is never deleted;
 *  - the encrypted file is removed before the metadata row, so a failure at
 *    either step produces an explicit [DeletionResult.PartialFailure] rather
 *    than a false success;
 *  - the caller must request deletion explicitly — no expiry or background
 *    retention policy exists.
 */
class EvidenceRetentionController(
    private val filesDir: File,
    private val metadataRepository: EvidenceMetadataRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun delete(evidenceId: String): DeletionResult = withContext(ioDispatcher) {
        if (evidenceId.isBlank()) return@withContext DeletionResult.NotFound

        val metadata = try {
            metadataRepository.findById(evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext DeletionResult.Failed(DeletionStage.METADATA_LOOKUP, e)
        } ?: return@withContext DeletionResult.NotFound

        val container = EncryptedContainerPath.resolve(filesDir, metadata.containerRelativePath)
            ?: return@withContext DeletionResult.Failed(DeletionStage.CONTAINER_PATH)

        val pathIsShared = try {
            when (val listed = metadataRepository.observeAll().first()) {
                is EvidenceListResult.Success -> listed.records.any {
                    it.evidenceId != metadata.evidenceId &&
                        it.containerRelativePath == metadata.containerRelativePath
                }

                is EvidenceListResult.Error -> return@withContext DeletionResult.Failed(
                    DeletionStage.METADATA_LOOKUP, listed.cause
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext DeletionResult.Failed(DeletionStage.METADATA_LOOKUP, e)
        }

        val containerDeleted = if (pathIsShared) {
            false
        } else {
            try {
                if (container.exists() && !container.isFile) {
                    return@withContext DeletionResult.Failed(DeletionStage.CONTAINER_DELETE)
                }
                if (container.exists() && !container.delete()) {
                    return@withContext DeletionResult.Failed(
                        DeletionStage.CONTAINER_DELETE,
                        IOException("Encrypted container could not be deleted")
                    )
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext DeletionResult.Failed(DeletionStage.CONTAINER_DELETE, e)
            }
        }

        when (val removed = metadataRepository.delete(metadata)) {
            MetadataDeletionResult.Deleted -> if (containerDeleted) {
                DeletionResult.Deleted
            } else {
                DeletionResult.PartialFailure(
                    containerDeleted = false,
                    metadataDeleted = true,
                    cause = IOException("Shared encrypted container was intentionally retained")
                )
            }

            MetadataDeletionResult.NotFound -> DeletionResult.PartialFailure(
                containerDeleted = containerDeleted,
                metadataDeleted = false
            )

            MetadataDeletionResult.ConflictingRecord -> DeletionResult.PartialFailure(
                containerDeleted = containerDeleted,
                metadataDeleted = false,
                cause = IOException("Metadata row changed before deletion")
            )

            is MetadataDeletionResult.Failed -> DeletionResult.PartialFailure(
                containerDeleted = containerDeleted,
                metadataDeleted = false,
                cause = removed.cause
            )
        }
    }
}
