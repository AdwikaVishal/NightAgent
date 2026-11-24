package com.nightagent.evidence.integrity

import com.nightagent.evidence.crypto.AuthenticationFailedException
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataEntity
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.RegistrationStatus
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeNge1Verifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Local integrity checks against stored metadata and published containers. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceIntegrityVerifierTest {

    @Test
    fun matchingFileAndMetadataVerifyWithExistingEngine() = runTest {
        val filesDir = Files.createTempDirectory("integrity_verified").toFile()
        val plaintext = File(filesDir, "capture.jpg").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        val relativePath = "evidence-encrypted/photos/valid.ng1"
        val target = File(filesDir, relativePath)
        target.parentFile?.mkdirs()

        val engine = realEngine()
        val encryption = engine.encrypt(plaintext, target, EvidenceType.PHOTO)
        val metadata = metadata(
            evidenceId = encryption.fileId.toString(),
            relativePath = relativePath,
            size = target.length(),
            digest = ContainerDigests.sha256Hex(target.readBytes())
        )
        val repository = repositoryWith(metadata)

        val result = EvidenceIntegrityVerifier.withEngine(filesDir, repository, engine)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.VERIFIED, result.status)
        assertEquals(metadata.evidenceId, result.evidenceId)
    }

    @Test
    fun missingFileReportsFileMissing() = runTest {
        val filesDir = Files.createTempDirectory("integrity_missing_file").toFile()
        val metadata = metadata(relativePath = "evidence-encrypted/photos/absent.ng1")
        val verifier = FakeNge1Verifier()

        val result = EvidenceIntegrityVerifier(filesDir, repositoryWith(metadata), verifier)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.FILE_MISSING, result.status)
        assertEquals(0, verifier.calls)
    }

    @Test
    fun changedSizeReportsSizeMismatchBeforeHashing() = runTest {
        val filesDir = Files.createTempDirectory("integrity_size").toFile()
        val relativePath = "evidence-encrypted/audio/clip.ng1"
        val container = writeContainer(filesDir, relativePath, ByteArray(256) { it.toByte() })
        val metadata = metadata(
            relativePath = relativePath,
            size = container.length() + 1,
            digest = ContainerDigests.sha256Hex(container.readBytes())
        )
        val verifier = FakeNge1Verifier()

        val result = EvidenceIntegrityVerifier(filesDir, repositoryWith(metadata), verifier)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.SIZE_MISMATCH, result.status)
        assertEquals(0, verifier.calls)
    }

    @Test
    fun sameSizeModificationReportsHashMismatchWithoutRepair() = runTest {
        val filesDir = Files.createTempDirectory("integrity_hash").toFile()
        val relativePath = "evidence-encrypted/video/clip.ng1"
        val container = writeContainer(filesDir, relativePath, ByteArray(256) { it.toByte() })
        val storedDigest = ContainerDigests.sha256Hex(container.readBytes())
        val metadata = metadata(
            relativePath = relativePath,
            size = container.length(),
            digest = storedDigest
        )
        val repository = repositoryWith(metadata)
        val verifier = FakeNge1Verifier()

        val modified = container.readBytes().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        container.writeBytes(modified)

        val result = EvidenceIntegrityVerifier(filesDir, repository, verifier)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.HASH_MISMATCH, result.status)
        assertEquals(0, verifier.calls)
        assertEquals(storedDigest, repository.findById(metadata.evidenceId)?.sha256)
        assertTrue(container.isFile)
        assertTrue(container.readBytes().contentEquals(modified))
    }

    @Test
    fun nge1AuthenticationFailureIsReportedWithoutChangingDigest() = runTest {
        val filesDir = Files.createTempDirectory("integrity_auth").toFile()
        val relativePath = "evidence-encrypted/photos/auth.ng1"
        val container = writeContainer(filesDir, relativePath, ByteArray(128) { 7 })
        val storedDigest = ContainerDigests.sha256Hex(container.readBytes())
        val metadata = metadata(
            relativePath = relativePath,
            size = container.length(),
            digest = storedDigest
        )
        val repository = repositoryWith(metadata)
        val verifier = FakeNge1Verifier().apply {
            failure = AuthenticationFailedException("simulated authentication failure")
        }

        val result = EvidenceIntegrityVerifier(filesDir, repository, verifier)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.NGE1_AUTHENTICATION_FAILED, result.status)
        assertEquals(1, verifier.calls)
        assertEquals(storedDigest, repository.findById(metadata.evidenceId)?.sha256)
        assertTrue(container.readBytes().contentEquals(ByteArray(128) { 7 }))
    }

    @Test
    fun absentMetadataReportsMetadataNotFound() = runTest {
        val filesDir = Files.createTempDirectory("integrity_no_metadata").toFile()
        val repository = RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao())

        val result = EvidenceIntegrityVerifier(filesDir, repository, FakeNge1Verifier())
            .verify("unknown-evidence")

        assertEquals(IntegrityStatus.METADATA_NOT_FOUND, result.status)
    }

    @Test
    fun metadataLookupFailureFailsClosed() = runTest {
        val filesDir = Files.createTempDirectory("integrity_lookup_error").toFile()
        val dao = FakeEvidenceMetadataDao().apply {
            findError = IllegalStateException("simulated lookup failure")
        }
        val repository = RoomEvidenceMetadataRepository(dao)
        val verifier = FakeNge1Verifier()

        val result = EvidenceIntegrityVerifier(filesDir, repository, verifier)
            .verify("evidence-1")

        assertEquals(IntegrityStatus.VERIFICATION_ERROR, result.status)
        assertEquals(0, verifier.calls)
    }

    @Test
    fun invalidStoredPathFailsClosed() = runTest {
        val filesDir = Files.createTempDirectory("integrity_invalid_path").toFile()
        val metadata = metadata(relativePath = "../plaintext/capture.jpg")
        val dao = FakeEvidenceMetadataDao()
        dao.seed(EvidenceMetadataEntity.from(metadata))
        val verifier = FakeNge1Verifier()

        val result = EvidenceIntegrityVerifier(
            filesDir,
            RoomEvidenceMetadataRepository(dao),
            verifier
        ).verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.VERIFICATION_ERROR, result.status)
        assertEquals(0, verifier.calls)
        assertFalse(result.detail?.contains("capture.jpg") ?: false)
    }

    private fun realEngine(): EvidenceEncryptionEngine = EvidenceEncryptionEngine(
        TestKeyAccess(listOf(TestMasterKey.seeded(0x55))),
        JvmAtomicFilePublisher()
    )

    private fun writeContainer(filesDir: File, relativePath: String, bytes: ByteArray): File =
        File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private suspend fun repositoryWith(metadata: EvidenceMetadata): RoomEvidenceMetadataRepository {
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        check(repository.register(metadata) == MetadataRegistrationResult.Registered)
        return repository
    }

    private fun metadata(
        evidenceId: String = "evidence-1",
        relativePath: String = "evidence-encrypted/photos/evidence-1.ng1",
        size: Long = 256L,
        digest: String = "0".repeat(64)
    ): EvidenceMetadata = EvidenceMetadata(
        evidenceId = evidenceId,
        mediaType = EvidenceType.PHOTO,
        originalExtension = "jpg",
        createdAtMillis = 1_700_000_000_000L,
        containerRelativePath = relativePath,
        containerSizeBytes = size,
        sha256 = digest,
        schemaVersion = EvidenceMetadata.CURRENT_SCHEMA_VERSION,
        registrationStatus = RegistrationStatus.STORED
    )
}
