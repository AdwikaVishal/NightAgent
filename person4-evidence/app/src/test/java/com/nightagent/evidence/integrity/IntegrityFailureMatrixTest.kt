package com.nightagent.evidence.integrity

import com.nightagent.evidence.crypto.AuthenticationFailedException
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.EvidenceKeyAccess
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.InvalidatedTestMasterKey
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeNge1Verifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Consolidated fail-closed matrix for integrity checks.
 *
 * Every negative case asserts an explicit non-VERIFIED status and that the
 * stored container/metadata were not repaired. Temporary files only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrityFailureMatrixTest {

    @Test
    fun missingEncryptedFileIsFileMissingNeverVerified() = runTest {
        val filesDir = Files.createTempDirectory("matrix_missing_file").toFile()
        val metadata = metadata(relativePath = "evidence-encrypted/photos/absent.ng1")
        val result = verifier(filesDir, repositoryWith(metadata), FakeNge1Verifier())
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.FILE_MISSING, result.status)
        assertNeverVerified(result)
    }

    @Test
    fun modifiedBytesAreHashMismatchNeverVerified() = runTest {
        val filesDir = Files.createTempDirectory("matrix_modified").toFile()
        val relativePath = "evidence-encrypted/photos/modified.ng1"
        val container = writeContainer(filesDir, relativePath, ByteArray(256) { it.toByte() })
        val original = container.readBytes()
        val metadata = metadata(
            relativePath = relativePath,
            size = container.length(),
            digest = ContainerDigests.sha256Hex(original)
        )
        container.writeBytes(original.also { it[3] = (it[3].toInt() xor 0x01).toByte() })

        val result = verifier(filesDir, repositoryWith(metadata), FakeNge1Verifier())
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.HASH_MISMATCH, result.status)
        assertNeverVerified(result)
        assertTrue(container.readBytes().contentEquals(original))
    }

    @Test
    fun sizeMismatchIsReportedBeforeNge1Verification() = runTest {
        val filesDir = Files.createTempDirectory("matrix_size").toFile()
        val relativePath = "evidence-encrypted/audio/size.ng1"
        val container = writeContainer(filesDir, relativePath, ByteArray(256) { 5 })
        val metadata = metadata(
            relativePath = relativePath,
            size = container.length() + 1,
            digest = ContainerDigests.sha256Hex(container.readBytes())
        )
        val nge1 = FakeNge1Verifier()

        val result = verifier(filesDir, repositoryWith(metadata), nge1).verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.SIZE_MISMATCH, result.status)
        assertNeverVerified(result)
        assertEquals(0, nge1.calls)
    }

    @Test
    fun invalidNge1AuthenticationIsExplicitNeverVerified() = runTest {
        val filesDir = Files.createTempDirectory("matrix_auth").toFile()
        val relativePath = "evidence-encrypted/video/auth.ng1"
        val container = writeContainer(filesDir, relativePath, ByteArray(128) { 9 })
        val metadata = metadata(
            relativePath = relativePath,
            size = container.length(),
            digest = ContainerDigests.sha256Hex(container.readBytes())
        )
        val nge1 = FakeNge1Verifier().apply {
            failure = AuthenticationFailedException("simulated tag failure")
        }

        val result = verifier(filesDir, repositoryWith(metadata), nge1).verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.NGE1_AUTHENTICATION_FAILED, result.status)
        assertNeverVerified(result)
        assertEquals(1, nge1.calls)
    }

    @Test
    fun missingMetadataIsExplicitNeverVerified() = runTest {
        val filesDir = Files.createTempDirectory("matrix_no_metadata").toFile()
        val result = verifier(filesDir, RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao()),
            FakeNge1Verifier()).verify("missing-evidence")

        assertEquals(IntegrityStatus.METADATA_NOT_FOUND, result.status)
        assertNeverVerified(result)
    }

    @Test
    fun unavailableKeystoreKeyIsExplicitNeverVerified() = runTest {
        val filesDir = Files.createTempDirectory("matrix_no_keystore_key").toFile()
        val plaintext = File(filesDir, "capture.jpg").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        val relativePath = "evidence-encrypted/photos/keystore.ng1"
        val container = File(filesDir, relativePath).apply { parentFile?.mkdirs() }

        val encryptingEngine = engine(TestKeyAccess(listOf(TestMasterKey.seeded(0x31))))
        encryptingEngine.encrypt(plaintext, container, EvidenceType.PHOTO)
        val containerBytes = container.readBytes()
        val metadata = metadata(
            evidenceId = "unavailable-key-evidence",
            relativePath = relativePath,
            size = container.length(),
            digest = ContainerDigests.sha256Hex(containerBytes)
        )

        // A fail-closed key source with no installed alias, mirroring an
        // invalidated/missing Keystore key on device.
        val noKeyEngine = engine(TestKeyAccess(emptyList()))
        val result = EvidenceIntegrityVerifier.withEngine(filesDir, repositoryWith(metadata), noKeyEngine)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.VERIFICATION_ERROR, result.status)
        assertNeverVerified(result)
        assertTrue(container.readBytes().contentEquals(containerBytes))
        assertTrue(result.detail?.contains(relativePath) != true)
    }

    @Test
    fun invalidatedKeystoreKeyReportsAuthenticationFailureNeverVerified() = runTest {
        val filesDir = Files.createTempDirectory("matrix_invalid_key").toFile()
        val plaintext = File(filesDir, "capture.m4a").apply { writeBytes(originalBytes) }
        val relativePath = "evidence-encrypted/audio/invalid-key.ng1"
        val container = File(filesDir, relativePath).apply { parentFile?.mkdirs() }

        engine(TestKeyAccess(listOf(TestMasterKey.seeded(0x77))))
            .encrypt(plaintext, container, EvidenceType.AUDIO)
        val containerBytes = container.readBytes()
        val metadata = metadata(
            evidenceId = "invalid-key-evidence",
            relativePath = relativePath,
            size = container.length(),
            digest = ContainerDigests.sha256Hex(containerBytes)
        )

        val invalidEngine = engine(TestKeyAccess(listOf(InvalidatedTestMasterKey(1))))
        val result = EvidenceIntegrityVerifier.withEngine(filesDir, repositoryWith(metadata), invalidEngine)
            .verify(metadata.evidenceId)

        assertEquals(IntegrityStatus.NGE1_AUTHENTICATION_FAILED, result.status)
        assertNeverVerified(result)
        assertTrue(container.readBytes().contentEquals(containerBytes))
    }

    private fun assertNeverVerified(result: IntegrityVerificationResult) {
        assertNotEquals(IntegrityStatus.VERIFIED, result.status)
    }

    private fun engine(keyAccess: EvidenceKeyAccess): EvidenceEncryptionEngine =
        EvidenceEncryptionEngine(keyAccess, JvmAtomicFilePublisher())

    private fun verifier(
        filesDir: File,
        repository: RoomEvidenceMetadataRepository,
        nge1: Nge1ContainerVerifier
    ): EvidenceIntegrityVerifier = EvidenceIntegrityVerifier(filesDir, repository, nge1)

    private fun writeContainer(filesDir: File, relativePath: String, bytes: ByteArray): File =
        File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private suspend fun repositoryWith(metadata: EvidenceMetadata): RoomEvidenceMetadataRepository {
        val repository = RoomEvidenceMetadataRepository(FakeEvidenceMetadataDao())
        check(repository.register(metadata) == MetadataRegistrationResult.Registered)
        return repository
    }

    private fun metadata(
        evidenceId: String = "matrix-evidence",
        relativePath: String = "evidence-encrypted/photos/matrix.ng1",
        size: Long = 256L,
        digest: String = "0".repeat(64)
    ): EvidenceMetadata = EvidenceMetadata(
        evidenceId = evidenceId,
        mediaType = EvidenceType.PHOTO,
        originalExtension = "jpg",
        createdAtMillis = 1_700_000_000_000L,
        containerRelativePath = relativePath,
        containerSizeBytes = size,
        sha256 = digest
    )

    private val originalBytes: ByteArray get() = ByteArray(512) { (it * 3).toByte() }
}
