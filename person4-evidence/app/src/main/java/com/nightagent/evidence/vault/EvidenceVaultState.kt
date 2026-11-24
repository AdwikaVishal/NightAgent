package com.nightagent.evidence.vault

import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.metadata.EvidenceListResult
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.RegistrationStatus
import com.nightagent.evidence.model.EvidenceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vault filter; ALL maps to a repository-level unfiltered query. */
enum class EvidenceFilter {
    ALL,
    PHOTO,
    AUDIO,
    VIDEO
}

/** UI state for the observable evidence list. */
sealed interface EvidenceVaultUiState {
    data object Loading : EvidenceVaultUiState
    data class Ready(val records: List<EvidenceMetadata>) : EvidenceVaultUiState
    data class Error(val message: String) : EvidenceVaultUiState
}

fun EvidenceFilter.mediaType(): EvidenceType? = when (this) {
    EvidenceFilter.ALL -> null
    EvidenceFilter.PHOTO -> EvidenceType.PHOTO
    EvidenceFilter.AUDIO -> EvidenceType.AUDIO
    EvidenceFilter.VIDEO -> EvidenceType.VIDEO
}

fun EvidenceFilter.label(): String = when (this) {
    EvidenceFilter.ALL -> "All"
    EvidenceFilter.PHOTO -> "Photo"
    EvidenceFilter.AUDIO -> "Audio"
    EvidenceFilter.VIDEO -> "Video"
}

fun EvidenceListResult.toVaultUiState(): EvidenceVaultUiState = when (this) {
    is EvidenceListResult.Success -> EvidenceVaultUiState.Ready(records)
    is EvidenceListResult.Error -> EvidenceVaultUiState.Error(
        cause.message ?: "Could not load evidence metadata."
    )
}

fun EvidenceType.label(): String = when (this) {
    EvidenceType.PHOTO -> "PHOTO"
    EvidenceType.AUDIO -> "AUDIO"
    EvidenceType.VIDEO -> "VIDEO"
}

fun EvidenceMetadata.mediaLabel(): String = mediaType.label()

fun shortenEvidenceId(evidenceId: String): String =
    if (evidenceId.length <= 14) evidenceId else "${evidenceId.take(8)}...${evidenceId.takeLast(4)}"

fun formatEvidenceTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

fun formatEvidenceSize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
}

fun registrationStatusLabel(status: RegistrationStatus): String = when (status) {
    RegistrationStatus.STORED -> "Stored"
    RegistrationStatus.PENDING_CLEANUP -> "Cleanup pending"
}

fun integrityStatusDescription(status: IntegrityStatus): String = when (status) {
    IntegrityStatus.VERIFIED ->
        "File size, SHA-256 and NGE1 authentication all matched."

    IntegrityStatus.FILE_MISSING ->
        "The published encrypted container is missing."

    IntegrityStatus.SIZE_MISMATCH ->
        "The container size differs from the recorded size."

    IntegrityStatus.HASH_MISMATCH ->
        "The recomputed SHA-256 does not match the recorded digest."

    IntegrityStatus.NGE1_AUTHENTICATION_FAILED ->
        "NGE1 AES-GCM authentication failed."

    IntegrityStatus.METADATA_NOT_FOUND ->
        "No local metadata record exists for this evidence ID."

    IntegrityStatus.VERIFICATION_ERROR ->
        "Verification could not be completed safely."
}
