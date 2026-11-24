package com.nightagent.evidence.devtest

import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.retention.EvidenceRetentionController
import com.nightagent.evidence.storage.Nge1EvidenceEncryptor
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Harness-only creation/deletion of a synthetic record. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase5DeletionTestControllerTest {

    @Test
    fun createThenDeleteRemovesBothContainerAndMetadata() = runTest {
        val fixture = fixture("phase5_delete_success")
        val controller = fixture.controller()

        val created = controller.create()

        assertTrue(created.success)
        assertNotNull(created.evidenceId)
        assertEquals(created.evidenceId, fixture.store.get())
        val metadata = fixture.repository.findById(created.evidenceId!!)
        assertNotNull(metadata)
        assertTrue(metadata!!.containerRelativePath.startsWith(Phase5DeletionTestController.TEST_DIRECTORY))
        assertTrue(File(fixture.filesDir, metadata.containerRelativePath).isFile)
        assertTrue(File(fixture.filesDir, Phase5DeletionTestController.STAGING_DIRECTORY)
            .listFiles().isNullOrEmpty())

        val deleted = controller.delete()

        assertTrue(deleted.statusText, deleted.success)
        assertTrue(deleted.encryptedFileRemoved)
        assertTrue(deleted.metadataRemoved)
        assertEquals(null, fixture.store.get())
        assertEquals(null, fixture.repository.findById(created.evidenceId))
        assertFalse(File(fixture.filesDir, metadata.containerRelativePath).exists())
    }

    @Test
    fun deleteWithoutCreatedRecordDoesNothing() = runTest {
        val fixture = fixture("phase5_delete_none")

        val result = fixture.controller().delete()

        assertFalse(result.success)
        assertTrue(result.statusText.contains("No temporary test record"))
        assertEquals(null, fixture.store.get())
    }

    @Test
    fun persistedIdForRealEvidenceIsRefused() = runTest {
        val fixture = fixture("phase5_delete_refusal")
        val realMetadata = fixture.createRealCaptureRecord("real-capture")
        fixture.store.save(realMetadata.evidenceId)
        val container = File(fixture.filesDir, realMetadata.containerRelativePath)

        val result = fixture.controller().delete()

        assertFalse(result.success)
        assertTrue(result.statusText.contains("Refusing"))
        assertTrue(container.isFile)
        assertNotNull(fixture.repository.findById(realMetadata.evidenceId))
        assertEquals(realMetadata.evidenceId, fixture.store.get())
    }

    @Test
    fun staleHarnessReferenceIsCleared() = runTest {
        val fixture = fixture("phase5_delete_stale")
        fixture.store.save("missing-evidence-id")

        val result = fixture.controller().delete()

        assertFalse(result.success)
        assertTrue(result.statusText.contains("already absent"))
        assertEquals(null, fixture.store.get())
    }

    private suspend fun fixture(name: String): Fixture {
        val filesDir = Files.createTempDirectory(name).toFile()
        val engine = EvidenceEncryptionEngine(
            TestKeyAccess(listOf(TestMasterKey.seeded(0x22))), JvmAtomicFilePublisher()
        )
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val retention = EvidenceRetentionController(filesDir, repository, UnconfinedTestDispatcher())
        return Fixture(filesDir, Nge1EvidenceEncryptor(engine), repository, retention, InMemoryStore())
    }

    private suspend fun Fixture.createRealCaptureRecord(name: String): EvidenceMetadata {
        val relativePath = "evidence-encrypted/photos/$name.ng1"
        val plaintext = File(filesDir, "$name.jpg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val container = File(filesDir, relativePath).apply { parentFile?.mkdirs() }
        encryptor.encrypt(plaintext, container, EvidenceType.PHOTO)
        val metadata = EvidenceMetadata(
            evidenceId = "$name-evidence",
            mediaType = EvidenceType.PHOTO,
            originalExtension = "jpg",
            createdAtMillis = 1_700_000_000_000L,
            containerRelativePath = relativePath,
            containerSizeBytes = container.length(),
            sha256 = ContainerDigests.sha256Hex(container.readBytes())
        )
        check(repository.register(metadata) is MetadataRegistrationResult.Registered)
        return metadata
    }

    private fun Fixture.controller(): Phase5DeletionTestController = Phase5DeletionTestController(
        filesDir = filesDir,
        encryptor = encryptor,
        metadataRepository = repository,
        retentionController = retention,
        recordStore = store,
        ioDispatcher = UnconfinedTestDispatcher()
    )

    private class InMemoryStore : Phase5TestRecordStore {
        private var id: String? = null
        override fun get(): String? = id
        override fun save(evidenceId: String) {
            id = evidenceId
        }
        override fun clear() {
            id = null
        }
    }

    private data class Fixture(
        val filesDir: File,
        val encryptor: Nge1EvidenceEncryptor,
        val repository: RoomEvidenceMetadataRepository,
        val retention: EvidenceRetentionController,
        val store: InMemoryStore
    )
}
