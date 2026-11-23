package com.nightagent.evidence.metadata

import com.nightagent.evidence.model.EvidenceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Outcome of registering one encrypted container's metadata. */
sealed interface MetadataRegistrationResult {
    data object Registered : MetadataRegistrationResult
    data object AlreadyRegistered : MetadataRegistrationResult
    data object ConflictingRecord : MetadataRegistrationResult
    data class Failed(val cause: Throwable) : MetadataRegistrationResult
}

/** Outcome of a status-only metadata update after plaintext cleanup failed. */
sealed interface MetadataStatusUpdateResult {
    data object Updated : MetadataStatusUpdateResult
    data object AlreadyPending : MetadataStatusUpdateResult
    data object NotFound : MetadataStatusUpdateResult
    data object ConflictingRecord : MetadataStatusUpdateResult
    data class Failed(val cause: Throwable) : MetadataStatusUpdateResult
}

/** Outcome of deleting one metadata row after an explicit delete request. */
sealed interface MetadataDeletionResult {
    data object Deleted : MetadataDeletionResult
    data object NotFound : MetadataDeletionResult
    data object ConflictingRecord : MetadataDeletionResult
    data class Failed(val cause: Throwable) : MetadataDeletionResult
}

/** Outcome of an observable metadata list query. Empty is a normal result. */
sealed interface EvidenceListResult {
    data class Success(val records: List<EvidenceMetadata>) : EvidenceListResult
    data class Error(val cause: Throwable) : EvidenceListResult
}

/** Repository boundary used by storage, integrity checks and the Evidence Vault. */
interface EvidenceMetadataRepository {
    suspend fun register(metadata: EvidenceMetadata): MetadataRegistrationResult
    suspend fun findById(evidenceId: String): EvidenceMetadata?
    suspend fun markCleanupPending(metadata: EvidenceMetadata): MetadataStatusUpdateResult
    suspend fun delete(metadata: EvidenceMetadata): MetadataDeletionResult
    fun observeAll(): Flow<EvidenceListResult>
    fun observeByMediaType(mediaType: EvidenceType): Flow<EvidenceListResult>
}

/**
 * Room-backed implementation. Duplicate IDs abort instead of replacing rows;
 * an existing row with different evidence is reported as a conflict. Cleanup
 * status changes are restricted to the exact matching record.
 */
class RoomEvidenceMetadataRepository(
    private val dao: EvidenceMetadataDao
) : EvidenceMetadataRepository {

    override suspend fun register(metadata: EvidenceMetadata): MetadataRegistrationResult {
        val existing = try {
            dao.findById(metadata.evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MetadataRegistrationResult.Failed(e)
        }

        if (existing != null) {
            return existing.registrationOutcomeFor(metadata)
        }

        return try {
            dao.insert(EvidenceMetadataEntity.from(metadata))
            MetadataRegistrationResult.Registered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A concurrent insert can win the race after our initial lookup.
            // Re-read before deciding this is a database failure.
            val winner = try {
                dao.findById(metadata.evidenceId)
            } catch (lookupError: CancellationException) {
                throw lookupError
            } catch (lookupError: Exception) {
                null
            }
            winner?.registrationOutcomeFor(metadata)
                ?: MetadataRegistrationResult.Failed(e)
        }
    }

    override suspend fun findById(evidenceId: String): EvidenceMetadata? =
        dao.findById(evidenceId)?.toDomain()

    override fun observeAll(): Flow<EvidenceListResult> =
        dao.observeAll().asListResult()

    override fun observeByMediaType(mediaType: EvidenceType): Flow<EvidenceListResult> =
        dao.observeByMediaType(mediaType).asListResult()

    override suspend fun markCleanupPending(
        metadata: EvidenceMetadata
    ): MetadataStatusUpdateResult {
        val current = try {
            dao.findById(metadata.evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MetadataStatusUpdateResult.Failed(e)
        } ?: return MetadataStatusUpdateResult.NotFound

        if (!current.toDomain().hasSameContentAs(metadata)) {
            return MetadataStatusUpdateResult.ConflictingRecord
        }
        if (current.registrationStatus == RegistrationStatus.PENDING_CLEANUP) {
            return MetadataStatusUpdateResult.AlreadyPending
        }

        return try {
            val updated = dao.updateRegistrationStatus(
                evidenceId = metadata.evidenceId,
                status = RegistrationStatus.PENDING_CLEANUP
            )
            if (updated > 0) {
                MetadataStatusUpdateResult.Updated
            } else {
                MetadataStatusUpdateResult.Failed(
                    IllegalStateException("Metadata row disappeared during cleanup update")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MetadataStatusUpdateResult.Failed(e)
        }
    }

    /**
     * Deletes a row only when the stored record still matches the exact metadata
     * supplied by the caller. This prevents a stale delete from removing a row
     * that was replaced in the meantime.
     */
    override suspend fun delete(metadata: EvidenceMetadata): MetadataDeletionResult {
        val current = try {
            dao.findById(metadata.evidenceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MetadataDeletionResult.Failed(e)
        } ?: return MetadataDeletionResult.NotFound

        if (!current.toDomain().hasSameContentAs(metadata)) {
            return MetadataDeletionResult.ConflictingRecord
        }

        return try {
            if (dao.deleteById(metadata.evidenceId) > 0) {
                MetadataDeletionResult.Deleted
            } else {
                MetadataDeletionResult.NotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MetadataDeletionResult.Failed(e)
        }
    }

    private fun Flow<List<EvidenceMetadataEntity>>.asListResult(): Flow<EvidenceListResult> =
        map { entities ->
            EvidenceListResult.Success(entities.map { it.toDomain() }) as EvidenceListResult
        }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(EvidenceListResult.Error(error))
            }

    private fun EvidenceMetadataEntity.registrationOutcomeFor(
        metadata: EvidenceMetadata
    ): MetadataRegistrationResult = if (toDomain().hasSameContentAs(metadata)) {
        MetadataRegistrationResult.AlreadyRegistered
    } else {
        MetadataRegistrationResult.ConflictingRecord
    }

    /** Status is excluded so a cleanup marker cannot mask different evidence. */
    private fun EvidenceMetadata.hasSameContentAs(other: EvidenceMetadata): Boolean =
        copy(registrationStatus = RegistrationStatus.STORED) ==
            other.copy(registrationStatus = RegistrationStatus.STORED)
}
