package com.nightagent.evidence.metadata

import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository behavior for duplicate IDs and database errors. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceMetadataRepositoryTest {

    @Test
    fun registrationThenLookupSucceeds() = runTest {
        val repository = RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao())
        val metadata = metadata()

        assertEquals(MetadataRegistrationResult.Registered, repository.register(metadata))
        assertEquals(metadata, repository.findById(metadata.evidenceId))
    }

    @Test
    fun duplicateIdWithSameEvidenceIsNotOverwritten() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val metadata = metadata()

        assertEquals(MetadataRegistrationResult.Registered, repository.register(metadata))
        assertEquals(MetadataRegistrationResult.AlreadyRegistered, repository.register(metadata))

        assertEquals(metadata, dao.stored(metadata.evidenceId)?.toDomain())
    }

    @Test
    fun duplicateIdWithDifferentEvidenceIsRejected() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val original = metadata()
        val conflicting = original.copy(sha256 = "a".repeat(64))

        assertEquals(MetadataRegistrationResult.Registered, repository.register(original))
        assertEquals(
            MetadataRegistrationResult.ConflictingRecord,
            repository.register(conflicting)
        )

        assertEquals(original, dao.stored(original.evidenceId)?.toDomain())
    }

    @Test
    fun insertFailureIsReportedSafely() = runTest {
        val dao = FakeEvidenceMetadataDao().apply {
            insertError = IllegalStateException("simulated database failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)
        val metadata = metadata()

        val outcome = repository.register(metadata)

        assertTrue(outcome is MetadataRegistrationResult.Failed)
        assertNull(dao.stored(metadata.evidenceId))
    }

    @Test
    fun concurrentDuplicateIsDetectedAfterInsertFailure() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val metadata = metadata()
        val winner = metadata.copy(sha256 = "b".repeat(64))

        dao.insertHook = {
            dao.seed(EvidenceMetadataEntity.from(winner))
            throw IllegalStateException("simulated unique constraint")
        }

        assertEquals(
            MetadataRegistrationResult.ConflictingRecord,
            repository.register(metadata)
        )
        assertEquals(winner, dao.stored(metadata.evidenceId)?.toDomain())
    }

    @Test
    fun lookupFailureIsPropagatedToCaller() = runTest {
        val dao = FakeEvidenceMetadataDao().apply {
            findError = IllegalStateException("simulated lookup failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)

        try {
            repository.findById("evidence-1")
            throw AssertionError("Expected lookup failure")
        } catch (e: IllegalStateException) {
            assertEquals("simulated lookup failure", e.message)
        }
    }

    @Test
    fun cleanupPendingUpdateChangesStatusOnly() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val metadata = metadata()
        repository.register(metadata)

        assertEquals(
            MetadataStatusUpdateResult.Updated,
            repository.markCleanupPending(metadata)
        )
        assertEquals(
            metadata.copy(registrationStatus = RegistrationStatus.PENDING_CLEANUP),
            repository.findById(metadata.evidenceId)
        )
        assertEquals(
            MetadataStatusUpdateResult.AlreadyPending,
            repository.markCleanupPending(metadata)
        )
    }

    @Test
    fun cleanupPendingUpdateRejectsConflictingEvidence() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        repository.register(metadata())
        val conflicting = metadata().copy(sha256 = "a".repeat(64))

        assertEquals(
            MetadataStatusUpdateResult.ConflictingRecord,
            repository.markCleanupPending(conflicting)
        )
        assertEquals(
            RegistrationStatus.STORED,
            repository.findById(conflicting.evidenceId)?.registrationStatus
        )
    }

    @Test
    fun cleanupPendingUpdateNotFoundIsReported() = runTest {
        val repository = RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao())

        assertEquals(
            MetadataStatusUpdateResult.NotFound,
            repository.markCleanupPending(metadata())
        )
    }

    @Test
    fun cleanupPendingUpdateFailureIsReported() = runTest {
        val dao = FakeEvidenceMetadataDao().apply {
            updateError = IllegalStateException("simulated update failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)
        repository.register(metadata())

        val outcome = repository.markCleanupPending(metadata())

        assertTrue(outcome is MetadataStatusUpdateResult.Failed)
    }

    @Test
    fun observeAllReturnsRecordsNewestFirst() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        dao.seed(EvidenceMetadataEntity.from(metadata().copy(evidenceId = "old", createdAtMillis = 1L)))
        dao.seed(EvidenceMetadataEntity.from(metadata().copy(evidenceId = "newest", createdAtMillis = 3L)))
        dao.seed(EvidenceMetadataEntity.from(metadata().copy(evidenceId = "middle", createdAtMillis = 2L)))

        val result = repository.observeAll().first()

        assertTrue(result is EvidenceListResult.Success)
        assertEquals(
            listOf("newest", "middle", "old"),
            (result as EvidenceListResult.Success).records.map { it.evidenceId }
        )
    }

    @Test
    fun observeAllReturnsEmptySuccessForEmptyDatabase() = runTest {
        val repository = RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao())

        val result = repository.observeAll().first()

        assertEquals(EvidenceListResult.Success(emptyList()), result)
    }

    @Test
    fun observeByMediaTypeFiltersRecords() = runTest {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        dao.seed(EvidenceMetadataEntity.from(metadata().copy(evidenceId = "photo", mediaType = EvidenceType.PHOTO)))
        dao.seed(EvidenceMetadataEntity.from(metadata().copy(evidenceId = "audio", mediaType = EvidenceType.AUDIO)))
        dao.seed(EvidenceMetadataEntity.from(metadata().copy(evidenceId = "video", mediaType = EvidenceType.VIDEO)))

        val result = repository.observeByMediaType(EvidenceType.AUDIO).first()

        assertTrue(result is EvidenceListResult.Success)
        assertEquals(
            listOf("audio"),
            (result as EvidenceListResult.Success).records.map { it.evidenceId }
        )
    }

    @Test
    fun observeErrorIsReportedWithoutThrowing() = runTest {
        val dao = FakeEvidenceMetadataDao().apply {
            observeError = IllegalStateException("simulated flow failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)

        val result = repository.observeAll().first()

        assertTrue(result is EvidenceListResult.Error)
        assertEquals(
            "simulated flow failure",
            (result as EvidenceListResult.Error).cause.message
        )
    }

    private fun metadata(): EvidenceMetadata = EvidenceMetadata(
        evidenceId = "evidence-1",
        mediaType = EvidenceType.PHOTO,
        originalExtension = "jpg",
        createdAtMillis = 1_700_000_000_000L,
        containerRelativePath = "evidence-encrypted/photos/evidence-1.ng1",
        containerSizeBytes = 2_048L,
        sha256 = "0".repeat(64)
    )
}
