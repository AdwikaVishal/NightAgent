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

/** Unit tests for the finalized-audio-to-encryption-and-metadata pipeline. */
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedAudioStoreTest {

    @Test
    fun successfulEncryptionRegistersAudioMetadataAndRemovesPlaintext() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, dao) = repository()
        val store = EncryptedAudioStore(filesDir, realEncryptor(), repository)

        val outcome = store.encryptAndPublish(plaintext)

        assertTrue(outcome is EncryptedEvidenceOutcome.Success)
        val success = outcome as EncryptedEvidenceOutcome.Success
        assertFalse(success.cleanupPending)

        val encrypted = File(
            EncryptedAudioFileName.encryptedAudioDirectory(filesDir),
            success.encryptedName
        )
        assertTrue(encrypted.isFile)
        assertFalse(plaintext.exists())

        val header = Nge1Container.readHeader(encrypted).metadata
        val recorded = repository.findById(header.fileId.toString())
        assertNotNull(recorded)
        assertEquals(EvidenceType.AUDIO, recorded!!.mediaType)
        assertEquals("m4a", recorded.originalExtension)
        assertEquals("evidence-encrypted/audio/${success.encryptedName}", recorded.containerRelativePath)
        assertEquals(encrypted.length(), recorded.containerSizeBytes)
        assertEquals(ContainerDigests.sha256Hex(encrypted.readBytes()), recorded.sha256)
        assertEquals(RegistrationStatus.STORED, recorded.registrationStatus)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun publishedContainerUsesAudioMediaTypeAndM4aExtension() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, _) = repository()
        val store = EncryptedAudioStore(filesDir, realEncryptor(), repository)

        val success = store.encryptAndPublish(plaintext) as EncryptedEvidenceOutcome.Success
        val encrypted = File(
            EncryptedAudioFileName.encryptedAudioDirectory(filesDir),
            success.encryptedName
        )
        val metadata = Nge1Container.readHeader(encrypted).metadata

        assertEquals(EvidenceType.AUDIO, metadata.mediaType)
        assertEquals("m4a", metadata.originalExtension)
        assertTrue(success.encryptedName.endsWith(".ng1"))
    }

    @Test
    fun repeatedRecordingsProduceDistinctEncryptedFiles() = runTest {
        val filesDir = filesDir()
        val first = recording(filesDir, "first.m4a")
        val second = recording(filesDir, "second.m4a")
        val (repository, _) = repository()
        val store = EncryptedAudioStore(filesDir, realEncryptor(), repository)

        val firstOutcome = store.encryptAndPublish(first) as EncryptedEvidenceOutcome.Success
        val secondOutcome = store.encryptAndPublish(second) as EncryptedEvidenceOutcome.Success

        assertNotEquals(firstOutcome.encryptedName, secondOutcome.encryptedName)
        val directory = EncryptedAudioFileName.encryptedAudioDirectory(filesDir)
        assertTrue(File(directory, firstOutcome.encryptedName).isFile)
        assertTrue(File(directory, secondOutcome.encryptedName).isFile)
    }

    @Test
    fun plaintextIsPreservedWhenEncryptionFails() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, dao) = repository()
        val encryptor = FakeEvidenceEncryptor(encryptImpl = { _, _, _ ->
            throw IOException("simulated encryption failure")
        })
        val store = EncryptedAudioStore(
            filesDir = filesDir,
            encryptor = encryptor,
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.ENCRYPTION_FAILED),
            outcome
        )
        assertEquals(EvidenceType.AUDIO, encryptor.receivedMediaType)
        assertTrue(plaintext.isFile)
        assertFalse(
            File(EncryptedAudioFileName.encryptedAudioDirectory(filesDir), "fixed.ng1").exists()
        )
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun plaintextIsPreservedWhenVerificationFails() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, dao) = repository()
        val encryptor = FakeEvidenceEncryptor(verifyImpl = {
            throw IOException("simulated verification failure")
        })
        val store = EncryptedAudioStore(
            filesDir = filesDir,
            encryptor = encryptor,
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" }
        )

        val outcome = store.encryptAndPublish(plaintext)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.VERIFICATION_FAILED),
            outcome
        )
        assertEquals(1, encryptor.verifyCalls)
        assertTrue(plaintext.isFile)
        assertFalse(
            File(EncryptedAudioFileName.encryptedAudioDirectory(filesDir), "fixed.ng1").exists()
        )
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun deletionFailureMarksMetadataCleanupPending() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, _) = repository()
        val store = EncryptedAudioStore(
            filesDir = filesDir,
            encryptor = FakeEvidenceEncryptor(),
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" },
            deletePlaintext = { false },
            metadataFactory = FakeMetadataFactory.created(
                evidenceId = "audio-cleanup-pending",
                mediaType = EvidenceType.AUDIO,
                originalExtension = "m4a"
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
            File(EncryptedAudioFileName.encryptedAudioDirectory(filesDir), "fixed.ng1").isFile
        )
        assertEquals(
            RegistrationStatus.PENDING_CLEANUP,
            repository.findById("audio-cleanup-pending")?.registrationStatus
        )
    }

    @Test
    fun existingDestinationIsNeverOverwritten() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, dao) = repository()
        val directory = EncryptedAudioFileName.encryptedAudioDirectory(filesDir).apply { mkdirs() }
        val target = File(directory, "fixed.ng1").apply { writeText("existing evidence") }
        val store = EncryptedAudioStore(
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
    fun incompleteOrMissingRecordingIsNeverEncryptedOrRegistered() = runTest {
        val filesDir = filesDir()
        val empty = File(filesDir, "empty.m4a").apply { createNewFile() }
        val missing = File(filesDir, "missing.m4a")
        val encryptor = FakeEvidenceEncryptor()
        val (repository, dao) = repository()
        val store = EncryptedAudioStore(filesDir, encryptor, repository)

        val emptyOutcome = store.encryptAndPublish(empty)
        val missingOutcome = store.encryptAndPublish(missing)

        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.CAPTURE_FILE_INVALID),
            emptyOutcome
        )
        assertEquals(
            EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.CAPTURE_FILE_INVALID),
            missingOutcome
        )
        assertEquals(0, encryptor.encryptCalls)
        assertEquals(0, encryptor.verifyCalls)
        assertFalse(EncryptedAudioFileName.encryptedAudioDirectory(filesDir).exists())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun digestFailurePreservesEncryptedContainerAndPlaintext() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val (repository, dao) = repository()
        val store = EncryptedAudioStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = repository,
            newEncryptedName = { "fixed.ng1" },
            metadataFactory = FakeMetadataFactory.failing(MetadataCreationResult.MissingFile)
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
            File(EncryptedAudioFileName.encryptedAudioDirectory(filesDir), "fixed.ng1").isFile
        )
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun metadataInsertionFailurePreservesBothFiles() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val dao = FakeEvidenceMetadataDao().apply {
            insertError = IllegalStateException("simulated insert failure")
        }
        val store = EncryptedAudioStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = RoomEvidenceMetadataRepository(dao),
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
            File(EncryptedAudioFileName.encryptedAudioDirectory(filesDir), "fixed.ng1").isFile
        )
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun conflictingEvidenceIdDoesNotOverwriteMetadata() = runTest {
        val filesDir = filesDir()
        val plaintext = recording(filesDir)
        val existing = EvidenceMetadata(
            evidenceId = "audio-conflict",
            mediaType = EvidenceType.AUDIO,
            originalExtension = "m4a",
            createdAtMillis = 1L,
            containerRelativePath = "evidence-encrypted/audio/existing.ng1",
            containerSizeBytes = 64L,
            sha256 = "a".repeat(64)
        )
        val dao = FakeEvidenceMetadataDao()
        dao.seed(EvidenceMetadataEntity.from(existing))
        val store = EncryptedAudioStore(
            filesDir = filesDir,
            encryptor = realEncryptor(),
            metadataRepository = RoomEvidenceMetadataRepository(dao),
            newEncryptedName = { "fixed.ng1" },
            metadataFactory = FakeMetadataFactory.created(
                evidenceId = "audio-conflict",
                mediaType = EvidenceType.AUDIO,
                originalExtension = "m4a",
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
            File(EncryptedAudioFileName.encryptedAudioDirectory(filesDir), "fixed.ng1").isFile
        )
        assertEquals(existing, dao.stored("audio-conflict")?.toDomain())
    }

    private fun repository(): Pair<RoomEvidenceMetadataRepository, FakeEvidenceMetadataDao> {
        val dao = FakeEvidenceMetadataDao()
        return RoomEvidenceMetadataRepository(dao) to dao
    }

    private fun filesDir(): File = Files.createTempDirectory("encrypted_audio_store_test").toFile()

    private fun recording(directory: File, name: String = "clip.m4a"): File =
        File(directory, name).apply { writeBytes(ByteArray(512) { it.toByte() }) }

    private fun realEncryptor(): EvidenceEncryptor {
        val key = TestMasterKey.seeded(0x77)
        val engine = EvidenceEncryptionEngine(
            TestKeyAccess(listOf(key)),
            JvmAtomicFilePublisher()
        )
        return Nge1EvidenceEncryptor(engine)
    }

    private class FakeEvidenceEncryptor(
        private val encryptImpl: (File, File, EvidenceType) -> Unit = { _, target, _ ->
            target.writeBytes(byteArrayOf(1, 2, 3, 4))
        },
        private val verifyImpl: (File) -> Unit = {}
    ) : EvidenceEncryptor {

        var encryptCalls: Int = 0
            private set
        var verifyCalls: Int = 0
            private set
        var receivedMediaType: EvidenceType? = null
            private set

        override fun encrypt(
            plaintext: File,
            target: File,
            mediaType: EvidenceType
        ): EncryptionResult {
            encryptCalls++
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
            verifyCalls++
            verifyImpl(encrypted)
        }
    }
}
