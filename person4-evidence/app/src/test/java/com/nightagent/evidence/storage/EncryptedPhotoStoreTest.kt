package com.nightagent.evidence.storage

import com.nightagent.evidence.crypto.EncryptionResult
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.Nge1Container
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataEntity
import com.nightagent.evidence.metadata.MetadataCreationResult
import com.nightagent.evidence.metadata.RegistrationStatus
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeMetadataFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Unit tests for the photo capture-to-encryption-and-metadata pipeline. */
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedPhotoStoreTest {

    @Test
    fun successfulEncryptionRegistersMetadataAndRemovesPlaintext() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val (repository, dao) = repository()
        val store = EncryptedPhotoStore(filesDir, realEncryptor(), repository)

        val outcome = store.encryptAndPublish(plaintext)

        assertTrue(outcome is EncryptedEvidenceOutcome.Success)
        val success = outcome as EncryptedEvidenceOutcome.Success
        assertFalse(success.cleanupPending)
        assertFalse(success.cleanupStatusPending)

        val encrypted = File(
            EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir),
            success.encryptedName
        )
        assertTrue(encrypted.isFile)
        assertFalse(plaintext.exists())

        val header = Nge1Container.readHeader(encrypted).metadata
        assertEquals(EvidenceType.PHOTO, header.mediaType)
        assertEquals("jpg", header.originalExtension)

        val recorded = repository.findById(header.fileId.toString())
        assertNotNull(recorded)
        assertEquals(EvidenceType.PHOTO, recorded!!.mediaType)
        assertEquals("evidence-encrypted/photos/${success.encryptedName}", recorded.containerRelativePath)
        assertEquals(encrypted.length(), recorded.containerSizeBytes)
        assertEquals(ContainerDigests.sha256Hex(encrypted.readBytes()), recorded.sha256)
        assertEquals(RegistrationStatus.STORED, recorded.registrationStatus)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun repeatedCapturesProduceDistinctEncryptedFiles() = runTest {
        val filesDir = filesDir()
        val firstPlaintext = plaintext(filesDir, "first.jpg")
        val secondPlaintext = plaintext(filesDir, "second.jpg")
        val (repository, _) = repository()
        val store = EncryptedPhotoStore(filesDir, realEncryptor(), repository)

        val first = store.encryptAndPublish(firstPlaintext) as EncryptedEvidenceOutcome.Success
        val second = store.encryptAndPublish(secondPlaintext) as EncryptedEvidenceOutcome.Success

        assertNotEquals(first.encryptedName, second.encryptedName)
        val directory = EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir)
        assertTrue(File(directory, first.encryptedName).isFile)
        assertTrue(File(directory, second.encryptedName).isFile)
    }

    @Test
    fun plaintextIsPreservedWhenEncryptionFails() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val (repository, dao) = repository()
        val encryptor = FakePhotoEvidenceEncryptor(encryptImpl = { _, _, _ ->
            throw IOException("simulated encryption failure")
        })
        val store = EncryptedPhotoStore(
            filesDir,
            encryptor,
            repository,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.ENCRYPTION_FAILED),
            outcome
        )
        assertTrue(plaintext.isFile)
        assertFalse(File(EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir), "fixed.ng1").exists())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun plaintextIsPreservedWhenVerificationFails() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val (repository, dao) = repository()
        val encryptor = FakePhotoEvidenceEncryptor(verifyImpl = {
            throw IOException("simulated verification failure")
        })
        val store = EncryptedPhotoStore(
            filesDir,
            encryptor,
            repository,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.VERIFICATION_FAILED),
            outcome
        )
        assertEquals(EvidenceType.PHOTO, encryptor.receivedMediaType)
        assertTrue(plaintext.isFile)
        assertFalse(File(EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir), "fixed.ng1").exists())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun deletionFailureMarksMetadataCleanupPending() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val (repository, _) = repository()
        val store = EncryptedPhotoStore(
            filesDir = filesDir,
            encryptor = FakePhotoEvidenceEncryptor(),
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" },
            deletePlaintext = { false },
            metadataFactory = FakeMetadataFactory.created(
                evidenceId = "photo-cleanup-pending",
                mediaType = EvidenceType.PHOTO,
                originalExtension = "jpg"
            )
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Success(
                encryptedName = "fixed.ng1",
                cleanupPending = true
            ),
            outcome
        )
        assertTrue(plaintext.isFile)
        assertTrue(
            File(EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir), "fixed.ng1").isFile
        )
        assertEquals(
            RegistrationStatus.PENDING_CLEANUP,
            repository.findById("photo-cleanup-pending")?.registrationStatus
        )
    }

    @Test
    fun existingDestinationIsNeverOverwritten() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val (repository, dao) = repository()
        val directory = EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir).apply { mkdirs() }
        val target = File(directory, "fixed.ng1").apply { writeText("existing evidence") }
        val store = EncryptedPhotoStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.STORAGE_PREPARATION),
            outcome
        )
        assertEquals("existing evidence", target.readText())
        assertTrue(plaintext.isFile)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun digestFailurePreservesEncryptedContainerAndPlaintext() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val (repository, dao) = repository()
        val store = EncryptedPhotoStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" },
            metadataFactory = FakeMetadataFactory.failing(MetadataCreationResult.UnreadableFile)
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.MetadataPending(
                encryptedName = "fixed.ng1",
                reason = MetadataOutcomeFailure.DIGEST_FAILED
            ),
            outcome
        )
        assertTrue(plaintext.isFile)
        assertTrue(
            File(EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir), "fixed.ng1").isFile
        )
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun metadataInsertionFailurePreservesBothFiles() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val dao = FakeEvidenceMetadataDao().apply {
            insertError = IllegalStateException("simulated insert failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)
        val store = EncryptedPhotoStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.MetadataPending(
                encryptedName = "fixed.ng1",
                reason = MetadataOutcomeFailure.REGISTRATION_FAILED
            ),
            outcome
        )
        assertTrue(plaintext.isFile)
        assertTrue(
            File(EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir), "fixed.ng1").isFile
        )
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun conflictingEvidenceIdDoesNotOverwriteMetadata() = runTest {
        val filesDir = filesDir()
        val plaintext = plaintext(filesDir)
        val existing = EvidenceMetadata(
            evidenceId = "photo-conflict",
            mediaType = EvidenceType.PHOTO,
            originalExtension = "jpg",
            createdAtMillis = 1L,
            containerRelativePath = "evidence-encrypted/photos/existing.ng1",
            containerSizeBytes = 64L,
            sha256 = "a".repeat(64)
        )
        val dao = FakeEvidenceMetadataDao()
        dao.seed(EvidenceMetadataEntity.from(existing))
        val store = EncryptedPhotoStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = RoomEvidenceMetadataRepository(dao),
            newEncryptedName = { "fixed.ng1" },
            metadataFactory = FakeMetadataFactory.created(
                evidenceId = "photo-conflict",
                mediaType = EvidenceType.PHOTO,
                originalExtension = "jpg",
                digest = "b".repeat(64)
            )
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.MetadataPending(
                encryptedName = "fixed.ng1",
                reason = MetadataOutcomeFailure.CONFLICTING_RECORD
            ),
            outcome
        )
        assertTrue(plaintext.isFile)
        assertTrue(
            File(EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir), "fixed.ng1").isFile
        )
        assertEquals(existing, dao.stored("photo-conflict")?.toDomain())
    }

    @Test
    fun invalidPlaintextDoesNotRegisterMetadata() = runTest {
        val filesDir = filesDir()
        val invalid = File(filesDir, "empty.jpg").apply { writeBytes(ByteArray(0)) }
        val (repository, dao) = repository()
        val store = EncryptedPhotoStore(filesDir, realEncryptor(), repository)

        val outcome = store.encryptAndPublish(invalid)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.CAPTURE_FILE_INVALID),
            outcome
        )
        assertTrue(dao.all().isEmpty())
    }

    private fun repository(): Pair<RoomEvidenceMetadataRepository, FakeEvidenceMetadataDao> {
        val dao = FakeEvidenceMetadataDao()
        return RoomEvidenceMetadataRepository(dao) to dao
    }

    private fun filesDir(): File = Files.createTempDirectory("encrypted_photo_store_test").toFile()

    private fun plaintext(directory: File, name: String = "clip.jpg"): File =
        File(directory, name).apply { writeBytes(ByteArray(512) { it.toByte() }) }

    private fun realEncryptor(): PhotoEvidenceEncryptor {
        val key = TestMasterKey.seeded(0x55)
        val engine = EvidenceEncryptionEngine(
            TestKeyAccess(listOf(key)),
            JvmAtomicFilePublisher()
        )
        return Nge1PhotoEvidenceEncryptor(engine)
    }

    private class FakePhotoEvidenceEncryptor(
        private val encryptImpl: (File, File, EvidenceType) -> Unit = { _, target, _ ->
            target.writeBytes(byteArrayOf(1, 2, 3, 4))
        },
        private val verifyImpl: (File) -> Unit = {}
    ) : PhotoEvidenceEncryptor {

        var receivedMediaType: EvidenceType? = null
            private set

        override fun encrypt(
            plaintext: File,
            target: File,
            mediaType: EvidenceType
        ): EncryptionResult {
            receivedMediaType = mediaType
            encryptImpl(plaintext, target, mediaType)
            return EncryptionResult(
                file = target,
                fileId = UUID.randomUUID(),
                mediaType = mediaType,
                plaintextLength = plaintext.length(),
                chunkCount = 1,
                keyVersion = 1
            )
        }

        override fun verify(encrypted: File) {
            verifyImpl(encrypted)
        }
    }
}
