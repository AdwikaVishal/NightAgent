package com.nightagent.evidence.devtest

import com.nightagent.evidence.access.EvidenceAccessController
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.metadata.EvidenceMetadata
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

/** Sanitized harness behavior around the real access controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase5AccessTestControllerTest {

    @Test
    fun successfulOpenReportsOnlySafeStatusAndCloseCleansUp() = runTest {
        val fixture = fixture("phase5_access_success")
        val controller = Phase5AccessTestController(fixture.access, UnconfinedTestDispatcher())

        val outcome = controller.open(fixture.metadata.evidenceId)

        assertTrue(outcome.success)
        assertEquals("PHOTO", outcome.mediaType)
        assertTrue(outcome.plaintextExists)
        assertTrue(controller.hasActiveSession())
        assertFalse(outcome.statusText.contains("capture.jpg"))
        assertFalse(outcome.statusText.contains(fixture.container.parentFile?.path ?: "no-parent"))

        val closed = controller.close()
        assertTrue(closed.hadSession)
        assertTrue(closed.cleanedUp)
        assertFalse(controller.hasActiveSession())
        assertEquals(0, fixture.access.activeTokenCount())
    }

    @Test
    fun integrityFailureNeverCreatesAnAccessSession() = runTest {
        val fixture = fixture("phase5_access_integrity")
        fixture.container.writeBytes(
            fixture.container.readBytes().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )
        val controller = Phase5AccessTestController(fixture.access, UnconfinedTestDispatcher())

        val outcome = controller.open(fixture.metadata.evidenceId)

        assertFalse(outcome.success)
        assertTrue(outcome.statusText.contains("HASH_MISMATCH"))
        assertEquals(null, outcome.mediaType)
        assertFalse(outcome.plaintextExists)
        assertFalse(controller.hasActiveSession())
        assertEquals(0, fixture.access.activeTokenCount())
    }

    @Test
    fun releaseAllClearsTheHarnessSessionAndTemporaryPlaintext() = runTest {
        val fixture = fixture("phase5_access_release")
        val controller = Phase5AccessTestController(fixture.access, UnconfinedTestDispatcher())
        val opened = controller.open(fixture.metadata.evidenceId)
        val temporaryFile = File(fixture.filesDir, ".evidence-access")

        controller.releaseAll()

        assertTrue(opened.success)
        assertTrue(temporaryFile.listFiles().isNullOrEmpty())
        assertFalse(controller.hasActiveSession())
        assertEquals(0, fixture.access.activeTokenCount())
    }

    private suspend fun fixture(name: String): Fixture {
        val filesDir = Files.createTempDirectory(name).toFile()
        val plaintext = File(filesDir, "capture.jpg").apply { writeBytes(ByteArray(256) { it.toByte() }) }
        val relativePath = "evidence-encrypted/photos/$name.ng1"
        val container = File(filesDir, relativePath).apply { parentFile?.mkdirs() }
        val engine = EvidenceEncryptionEngine(
            TestKeyAccess(listOf(TestMasterKey.seeded(0x11))), JvmAtomicFilePublisher()
        )
        engine.encrypt(plaintext, container, EvidenceType.PHOTO)

        val metadata = EvidenceMetadata(
            evidenceId = "$name-evidence",
            mediaType = EvidenceType.PHOTO,
            originalExtension = "jpg",
            createdAtMillis = 1_700_000_000_000L,
            containerRelativePath = relativePath,
            containerSizeBytes = container.length(),
            sha256 = ContainerDigests.sha256Hex(container.readBytes())
        )
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        check(repository.register(metadata) is MetadataRegistrationResult.Registered)
        val verifier = EvidenceIntegrityVerifier.withEngine(filesDir, repository, engine)
        val access = EvidenceAccessController(
            filesDir, repository, verifier, engine, UnconfinedTestDispatcher()
        )
        return Fixture(filesDir, container, metadata, access)
    }

    private data class Fixture(
        val filesDir: File,
        val container: File,
        val metadata: EvidenceMetadata,
        val access: EvidenceAccessController
    )
}
