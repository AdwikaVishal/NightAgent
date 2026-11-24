package com.nightagent.evidence.metadata

import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository-level deletion never removes a changed or absent row. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceMetadataDeletionTest {

    @Test
    fun exactMatchIsDeleted() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val metadata = metadata()
        check(repository.register(metadata) is MetadataRegistrationResult.Registered)

        assertEquals(MetadataDeletionResult.Deleted, repository.delete(metadata))
        assertEquals(null, repository.findById(metadata.evidenceId))
    }

    @Test
    fun changedRowIsNotDeleted() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val original = metadata()
        check(repository.register(original) is MetadataRegistrationResult.Registered)

        val stale = original.copy(sha256 = "b".repeat(64))
        assertEquals(MetadataDeletionResult.ConflictingRecord, repository.delete(stale))
        assertNotNull(repository.findById(original.evidenceId))
    }

    @Test
    fun cleanupStatusDifferenceStillMatchesSameEvidence() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val original = metadata()
        check(repository.register(original) is MetadataRegistrationResult.Registered)

        val marked = original.copy(registrationStatus = RegistrationStatus.PENDING_CLEANUP)
        assertEquals(MetadataDeletionResult.Deleted, repository.delete(marked))
    }

    @Test
    fun absentRowReportsNotFound() = runTest {
        val repository = RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao())
        assertEquals(MetadataDeletionResult.NotFound, repository.delete(metadata()))
    }

    @Test
    fun databaseFailureReportsFailed() = runTest {
        val dao = FakeEvidenceMetadataDao().apply {
            deleteError = IllegalStateException("simulated delete failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)
        val metadata = metadata()
        check(repository.register(metadata) is MetadataRegistrationResult.Registered)

        val result = repository.delete(metadata)
        assertTrue(result is MetadataDeletionResult.Failed)
        assertNotNull(repository.findById(metadata.evidenceId))
    }

    private fun metadata(): EvidenceMetadata = EvidenceMetadata(
        evidenceId = "delete-evidence",
        mediaType = EvidenceType.PHOTO,
        originalExtension = "jpg",
        createdAtMillis = 1_700_000_000_000L,
        containerRelativePath = "evidence-encrypted/photos/delete.ng1",
        containerSizeBytes = 128L,
        sha256 = "a".repeat(64)
    )
}
