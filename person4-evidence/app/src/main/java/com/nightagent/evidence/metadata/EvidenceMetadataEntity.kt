package com.nightagent.evidence.metadata

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nightagent.evidence.model.EvidenceType

/** Room row for one published NGE1 container. */
@Entity(tableName = EvidenceMetadataEntity.TABLE_NAME)
data class EvidenceMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = COLUMN_EVIDENCE_ID)
    val evidenceId: String,

    @ColumnInfo(name = COLUMN_MEDIA_TYPE)
    val mediaType: EvidenceType,

    @ColumnInfo(name = COLUMN_ORIGINAL_EXTENSION)
    val originalExtension: String,

    @ColumnInfo(name = COLUMN_CREATED_AT_MILLIS)
    val createdAtMillis: Long,

    @ColumnInfo(name = COLUMN_CONTAINER_RELATIVE_PATH)
    val containerRelativePath: String,

    @ColumnInfo(name = COLUMN_CONTAINER_SIZE_BYTES)
    val containerSizeBytes: Long,

    @ColumnInfo(name = COLUMN_SHA256)
    val sha256: String,

    @ColumnInfo(name = COLUMN_SCHEMA_VERSION)
    val schemaVersion: Int,

    @ColumnInfo(name = COLUMN_REGISTRATION_STATUS)
    val registrationStatus: RegistrationStatus
) {
    fun toDomain(): EvidenceMetadata = EvidenceMetadata(
        evidenceId = evidenceId,
        mediaType = mediaType,
        originalExtension = originalExtension,
        createdAtMillis = createdAtMillis,
        containerRelativePath = containerRelativePath,
        containerSizeBytes = containerSizeBytes,
        sha256 = sha256,
        schemaVersion = schemaVersion,
        registrationStatus = registrationStatus
    )

    companion object {
        const val TABLE_NAME: String = "evidence_metadata"
        const val COLUMN_EVIDENCE_ID: String = "evidence_id"
        const val COLUMN_MEDIA_TYPE: String = "media_type"
        const val COLUMN_ORIGINAL_EXTENSION: String = "original_extension"
        const val COLUMN_CREATED_AT_MILLIS: String = "created_at_millis"
        const val COLUMN_CONTAINER_RELATIVE_PATH: String = "container_relative_path"
        const val COLUMN_CONTAINER_SIZE_BYTES: String = "container_size_bytes"
        const val COLUMN_SHA256: String = "sha256_hex"
        const val COLUMN_SCHEMA_VERSION: String = "schema_version"
        const val COLUMN_REGISTRATION_STATUS: String = "registration_status"

        fun from(metadata: EvidenceMetadata): EvidenceMetadataEntity = EvidenceMetadataEntity(
            evidenceId = metadata.evidenceId,
            mediaType = metadata.mediaType,
            originalExtension = metadata.originalExtension,
            createdAtMillis = metadata.createdAtMillis,
            containerRelativePath = metadata.containerRelativePath,
            containerSizeBytes = metadata.containerSizeBytes,
            sha256 = metadata.sha256,
            schemaVersion = metadata.schemaVersion,
            registrationStatus = metadata.registrationStatus
        )
    }
}
