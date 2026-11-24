package com.nightagent.evidence.metadata

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nightagent.evidence.model.EvidenceType
import kotlinx.coroutines.flow.Flow

/** Suspend DAO for local evidence metadata; never exposes plaintext paths. */
@Dao
interface EvidenceMetadataDao {

    @Query("SELECT * FROM evidence_metadata WHERE evidence_id = :evidenceId LIMIT 1")
    suspend fun findById(evidenceId: String): EvidenceMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EvidenceMetadataEntity)

    /** Status-only update; digest, path and size columns are never touched. */
    @Query(
        "UPDATE evidence_metadata SET registration_status = :status " +
            "WHERE evidence_id = :evidenceId"
    )
    suspend fun updateRegistrationStatus(
        evidenceId: String,
        status: RegistrationStatus
    ): Int

    /** Deletes exactly one row by id. Returns the number of rows removed. */
    @Query("DELETE FROM evidence_metadata WHERE evidence_id = :evidenceId")
    suspend fun deleteById(evidenceId: String): Int

    @Query("SELECT * FROM evidence_metadata ORDER BY created_at_millis DESC")
    suspend fun all(): List<EvidenceMetadataEntity>

    @Query("SELECT * FROM evidence_metadata ORDER BY created_at_millis DESC")
    fun observeAll(): Flow<List<EvidenceMetadataEntity>>

    @Query(
        "SELECT * FROM evidence_metadata WHERE media_type = :mediaType " +
            "ORDER BY created_at_millis DESC"
    )
    fun observeByMediaType(mediaType: EvidenceType): Flow<List<EvidenceMetadataEntity>>
}
