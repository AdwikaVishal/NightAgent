package com.nightagent.evidence.access

import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.EvidenceKeyAccess
import com.nightagent.evidence.crypto.EvidenceMasterKey
import com.nightagent.evidence.crypto.InvalidatedTestMasterKey
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataEntity
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeNge1Verifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM coverage for scoped plaintext access; all files are temporary. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceAccessControllerTest {

    @Test
    fun verifiedEvidenceOpensTemporaryPlaintextAndReleasesIt() = runTest {
        val fixture = fixture("access_success")
        val controller = controller(fixture)

        val result = controller.open(fixture.metadata.evidenceId)
        assertTrue(result is EvidenceAccessResult.Ready)
        val token = (result as EvidenceAccessResult.Ready).token
        assertTrue(token.plaintextFile.isFile)
        assertTrue(token.plaintextFile.readBytes().contentEquals(fixture.plaintext.readBytes()))
        assertEquals(fixture.metadata.mediaType, token.mediaType)
        assertEquals("jpg", token.originalExtension)
        assertEquals(1, controller.activeTokenCount())
        assertFalse(token.toString().contains(token.plaintextFile.path))
        assertFalse(token.toString().contains("evidence-encrypted"))

        token.close()
        assertFalse(token.plaintextFile.exists())
        assertFalse(token.plaintextFile.parentFile?.exists() ?: false)
        assertEquals(0, controller.activeTokenCount())
        token.close() // idempotent
    }

    @Test
    fun integrityFailureNeverProducesPlaintext() = runTest {
        val fixture = fixture("access_integrity_failure")
        fixture.container.writeBytes(
            fixture.container.readBytes().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        )
        val controller = controller(fixture)

        val result = controller.open(fixture.metadata.evidenceId)

        assertTrue(result is EvidenceAccessResult.Denied)
        assertEquals(IntegrityStatus.HASH_MISMATCH, (result as EvidenceAccessResult.Denied).status)
        assertEquals(0, controller.activeTokenCount())
    }

    @Test
    fun missingMetadataIsDenied() = runTest {
        val fixture = fixture("access_no_metadata")
        val controller = controller(fixture)

        val result = controller.open("unknown-evidence")

        assertTrue(result is EvidenceAccessResult.Denied)
        assertEquals(IntegrityStatus.METADATA_NOT_FOUND, (result as EvidenceAccessResult.Denied).status)
    }

    @Test
    fun unsafeStoredPathIsDeniedBeforeAnyDecryption() = runTest {
        val fixture = fixture("access_unsafe_path")
        val unsafeMetadata = fixture.metadata.copy(
            evidenceId = "unsafe-path-evidence",
            containerRelativePath = "../plaintext/capture.ng1"
        )
        fixture.dao.seed(EvidenceMetadataEntity.from(unsafeMetadata))
        val controller = controller(fixture)

        val result = controller.open(unsafeMetadata.evidenceId)

        assertTrue(result is EvidenceAccessResult.Denied)
        assertEquals(IntegrityStatus.VERIFICATION_ERROR, (result as EvidenceAccessResult.Denied).status)
        assertEquals(0, controller.activeTokenCount())
    }

    @Test
    fun decryptionFailureRemovesTemporaryDirectory() = runTest {
        val fixture = fixture("access_decrypt_failure")
        val failingEngine = EvidenceEncryptionEngine(
            InvalidDecryptionKeyAccess(fixture.keyAccess), JvmAtomicFilePublisher()
        )
        val controller = controller(fixture, decryptionEngine = failingEngine)

        val result = controller.open(fixture.metadata.evidenceId)

        assertTrue(result is EvidenceAccessResult.Failed)
        assertEquals(EvidenceAccessStage.DECRYPT, (result as EvidenceAccessResult.Failed).stage)
        assertTrue(temporaryDirectory(fixture).listFiles().isNullOrEmpty())
        assertEquals(0, controller.activeTokenCount())
    }

    @Test
    fun successiveOpensUseDistinctTargetsInsteadOfOverwriting() = runTest {
        val fixture = fixture("access_distinct_targets")
        val controller = controller(fixture)

        val first = controller.open(fixture.metadata.evidenceId) as EvidenceAccessResult.Ready
        val second = controller.open(fixture.metadata.evidenceId) as EvidenceAccessResult.Ready

        assertNotEquals(first.token.plaintextFile.path, second.token.plaintextFile.path)
        assertEquals(2, controller.activeTokenCount())
        controller.releaseAll()
        assertEquals(0, controller.activeTokenCount())
        assertFalse(first.token.plaintextFile.exists())
        assertFalse(second.token.plaintextFile.exists())
    }

    @Test
    fun cancellationDoesNotLeaveTemporaryPlaintext() = runTest {
        val fixture = fixture("access_cancellation")
        val cancellingEngine = EvidenceEncryptionEngine(
            CancellingDecryptionKeyAccess(fixture.keyAccess), JvmAtomicFilePublisher()
        )
        val controller = controller(fixture, decryptionEngine = cancellingEngine)

        try {
            controller.open(fixture.metadata.evidenceId)
            throw AssertionError("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(temporaryDirectory(fixture).listFiles().isNullOrEmpty())
            assertEquals(0, controller.activeTokenCount())
        }
    }

    private fun temporaryDirectory(fixture: Fixture): File = File(fixture.filesDir, ".evidence-access")

    private fun controller(
        fixture: Fixture,
        decryptionEngine: EvidenceEncryptionEngine = fixture.engine,
        verifier: EvidenceIntegrityVerifier = fixture.verifier
    ): EvidenceAccessController = EvidenceAccessController(
        filesDir = fixture.filesDir,
        metadataRepository = fixture.repository,
        integrityVerifier = verifier,
        engine = decryptionEngine,
        ioDispatcher = UnconfinedTestDispatcher()
    )

    private suspend fun fixture(name: String): Fixture {
        val path = "evidence-encrypted/photos/$name.ng1"
        val filesDir = Files.createTempDirectory(name).toFile()
        val plaintext = File(filesDir, "capture.jpg").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        val container = File(filesDir, path).apply { parentFile?.mkdirs() }
        val keyAccess = TestKeyAccess(listOf(TestMasterKey.seeded(0x5A)))
        val engine = EvidenceEncryptionEngine(keyAccess, JvmAtomicFilePublisher())
        engine.encrypt(plaintext, container, EvidenceType.PHOTO)

        val metadata = EvidenceMetadata(
            evidenceId = "$name-evidence",
            mediaType = EvidenceType.PHOTO,
            originalExtension = "jpg",
            createdAtMillis = 1_700_000_000_000L,
            containerRelativePath = path,
            containerSizeBytes = container.length(),
            sha256 = ContainerDigests.sha256Hex(container.readBytes())
        )
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        check(repository.register(metadata) == MetadataRegistrationResult.Registered)
        val verifier = EvidenceIntegrityVerifier.withEngine(filesDir, repository, engine)
        return Fixture(filesDir, plaintext, container, keyAccess, engine, metadata, repository, verifier, dao)
    }

    private class InvalidDecryptionKeyAccess(
        private val delegate: EvidenceKeyAccess
    ) : EvidenceKeyAccess by delegate {
        override fun keyForDecryption(version: Int): EvidenceMasterKey =
            InvalidatedTestMasterKey(version)
    }

    private class CancellingDecryptionKeyAccess(
        private val delegate: EvidenceKeyAccess
    ) : EvidenceKeyAccess by delegate {
        override fun keyForDecryption(version: Int): EvidenceMasterKey = throw CancellationException()
    }

    private data class Fixture(
        val filesDir: File,
        val plaintext: File,
        val container: File,
        val keyAccess: TestKeyAccess,
        val engine: EvidenceEncryptionEngine,
        val metadata: EvidenceMetadata,
        val repository: RoomEvidenceMetadataRepository,
        val verifier: EvidenceIntegrityVerifier,
        val dao: FakeEvidenceMetadataDao
    )
}
