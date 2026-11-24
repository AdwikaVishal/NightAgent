package com.nightagent.evidence.vault

import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.metadata.EvidenceListResult
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.RegistrationStatus
import com.nightagent.evidence.model.EvidenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure UI-state and display helpers used by the Evidence Vault. */
class EvidenceVaultStateTest {

    @Test
    fun listResultMapsToReadyStateIncludingEmptyList() {
        val metadata = metadata()

        assertEquals(
            EvidenceVaultUiState.Ready(listOf(metadata)),
            EvidenceListResult.Success(listOf(metadata)).toVaultUiState()
        )
        assertEquals(
            EvidenceVaultUiState.Ready(emptyList()),
            EvidenceListResult.Success(emptyList()).toVaultUiState()
        )
    }

    @Test
    fun listErrorMapsToGracefulErrorState() {
        val state = EvidenceListResult.Error(IllegalStateException("database unavailable"))
            .toVaultUiState()

        assertTrue(state is EvidenceVaultUiState.Error)
        assertEquals("database unavailable", (state as EvidenceVaultUiState.Error).message)
    }

    @Test
    fun filterMapsToRepositoryMediaType() {
        assertEquals(null, EvidenceFilter.ALL.mediaType())
        assertEquals(EvidenceType.PHOTO, EvidenceFilter.PHOTO.mediaType())
        assertEquals(EvidenceType.AUDIO, EvidenceFilter.AUDIO.mediaType())
        assertEquals(EvidenceType.VIDEO, EvidenceFilter.VIDEO.mediaType())
    }

    @Test
    fun displayHelpersProduceReadableValues() {
        val record = metadata()

        assertEquals("PHOTO", record.mediaLabel())
        assertEquals("12345678...abcd", shortenEvidenceId("12345678-0000-0000-0000-00000000abcd"))
        assertEquals("short-id", shortenEvidenceId("short-id"))
        assertEquals("Cleanup pending", registrationStatusLabel(RegistrationStatus.PENDING_CLEANUP))
        assertEquals("Stored", registrationStatusLabel(RegistrationStatus.STORED))
        assertEquals("1.0 KB", formatEvidenceSize(1_024L))
        assertEquals("512 B", formatEvidenceSize(512L))
    }

    @Test
    fun integrityStatusDescriptionsCoverEveryStatus() {
        IntegrityStatus.entries.forEach { status ->
            assertTrue(integrityStatusDescription(status).isNotBlank())
        }
    }

    private fun metadata(): EvidenceMetadata = EvidenceMetadata(
        evidenceId = "12345678-0000-0000-0000-00000000abcd",
        mediaType = EvidenceType.PHOTO,
        originalExtension = "jpg",
        createdAtMillis = 1_700_000_000_000L,
        containerRelativePath = "evidence-encrypted/photos/x.ng1",
        containerSizeBytes = 2_048L,
        sha256 = "0".repeat(64),
        registrationStatus = RegistrationStatus.PENDING_CLEANUP
    )
}
