package com.nightagent.evidence.retention

import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataEntity
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Explicit-deletion safety coverage using only temporary files. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceRetentionControllerTest {

    @Test
    fun successfulDeleteRemovesContainerAndMetadata() = runTest {
        val fixture = fixture("retention_success")
        val controller = controller(fixture)

        val result = controller.delete(fixture.metadata.evidenceId)

        assertEquals(DeletionResult.Deleted, result)
        assertFalse(fixture.container.exists())
        assertEquals(null, fixture.repository.findById(fixture.metadata.evidenceId))
    }

    @Test
    fun missingMetadataReturnsNotFoundAndTouchesNothing() = runTest {
        val fixture = fixture("retention_missing")
        val unknown = File(fixture.filesDir, "evidence-encrypted/photos/unknown.ng1")

        val result = controller(fixture).delete("unknown-evidence")

        assertEquals(DeletionResult.NotFound, result)
        assertTrue(fixture.container.exists())
        assertFalse(unknown.exists())
    }

    @Test
    fun blankIdReturnsNotFound() = runTest {
        val fixture = fixture("retention_blank")
        assertEquals(DeletionResult.NotFound, controller(fixture).delete("   "))
        assertTrue(fixture.container.exists())
    }

    @Test
    fun unsafeStoredPathIsRejectedWithoutDeletingAnything() = runTest {
        val fixture = fixture("retention_unsafe_path")
        val unsafe = fixture.metadata.copy(
            evidenceId = "unsafe-evidence",
            containerRelativePath = "../elsewhere/other.ng1"
        )
        fixture.dao.seed(EvidenceMetadataEntity.from(unsafe))

        val result = controller(fixture).delete(unsafe.evidenceId)

        assertTrue(result is DeletionResult.Failed)
        assertEquals(DeletionStage.CONTAINER_PATH, (result as DeletionResult.Failed).stage)
        assertTrue(fixture.container.exists())
        assertEquals(unsafe.evidenceId, fixture.repository.findById(unsafe.evidenceId)?.evidenceId)
    }

    @Test
    fun sharedContainerIsRetainedAndPartialResultReported() = runTest {
        val fixture = fixture("retention_shared")
        val other = fixture.metadata.copy(
            evidenceId = "other-evidence",
            createdAtMillis = fixture.metadata.createdAtMillis + 1
        )
        fixture.dao.seed(EvidenceMetadataEntity.from(other))

        val result = controller(fixture).delete(fixture.metadata.evidenceId)

        assertTrue(result is DeletionResult.PartialFailure)
        val partial = result as DeletionResult.PartialFailure
        assertFalse(partial.containerDeleted)
        assertTrue(partial.metadataDeleted)
        assertTrue(fixture.container.exists())
        assertEquals(null, fixture.repository.findById(fixture.metadata.evidenceId))
        assertEquals(other.evidenceId, fixture.repository.findById(other.evidenceId)?.evidenceId)
    }

    @Test
    fun metadataDeleteFailureIsExplicitPartialFailure() = runTest {
        val fixture = fixture("retention_metadata_failure")
        fixture.dao.deleteError = IllegalStateException("simulated metadata delete failure")

        val result = controller(fixture).delete(fixture.metadata.evidenceId)

        assertTrue(result is DeletionResult.PartialFailure)
        val partial = result as DeletionResult.PartialFailure
        assertTrue(partial.containerDeleted)
        assertFalse(partial.metadataDeleted)
        assertFalse(fixture.container.exists())
        assertEquals(fixture.metadata.evidenceId, fixture.repository.findById(fixture.metadata.evidenceId)?.evidenceId)
    }

    @Test
    fun containerDeleteFailureLeavesMetadataIntact() = runTest {
        val fixture = fixture("retention_container_failure")
        // A directory at the container path is not a valid .ng1 file and must
        // not be deleted by this operation.
        fixture.container.delete()
        assertTrue(fixture.container.mkdirs())

        val result = controller(fixture).delete(fixture.metadata.evidenceId)

        assertTrue(result is DeletionResult.Failed)
        assertEquals(DeletionStage.CONTAINER_DELETE, (result as DeletionResult.Failed).stage)
        assertTrue(fixture.container.exists())
        assertEquals(fixture.metadata.evidenceId, fixture.repository.findById(fixture.metadata.evidenceId)?.evidenceId)
    }

    private fun controller(fixture: Fixture): EvidenceRetentionController =
        EvidenceRetentionController(
            filesDir = fixture.filesDir,
            metadataRepository = fixture.repository,
            ioDispatcher = UnconfinedTestDispatcher()
        )

    private suspend fun fixture(name: String): Fixture {
        val filesDir = Files.createTempDirectory(name).toFile()
        val relativePath = "evidence-encrypted/photos/$name.ng1"
        val container = File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(128) { it.toByte() })
        }
        val metadata = EvidenceMetadata(
            evidenceId = "$name-evidence",
            mediaType = EvidenceType.PHOTO,
            originalExtension = "jpg",
            createdAtMillis = 1_700_000_000_000L,
            containerRelativePath = relativePath,
            containerSizeBytes = container.length(),
            sha256 = "a".repeat(64)
        )
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        check(repository.register(metadata) is MetadataRegistrationResult.Registered)
        return Fixture(filesDir, container, metadata, repository, dao)
    }

    private data class Fixture(
        val filesDir: File,
        val container: File,
        val metadata: EvidenceMetadata,
        val repository: RoomEvidenceMetadataRepository,
        val dao: FakeEvidenceMetadataDao
    )
}
