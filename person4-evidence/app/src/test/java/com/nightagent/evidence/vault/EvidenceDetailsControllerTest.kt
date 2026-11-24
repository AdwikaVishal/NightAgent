package com.nightagent.evidence.vault

import com.nightagent.evidence.crypto.AuthenticationFailedException
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.integrity.Nge1ContainerVerifier
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataEntity
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeNge1Verifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Controller behavior for on-demand, read-only integrity verification. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceDetailsControllerTest {

    @Test
    fun successfulVerificationDoesNotModifyStoredMetadata() = runTest {
        val fixture = fixture()
        val verifier = FakeNge1Verifier()
        val controller = EvidenceDetailsController(fixture.repository, fixture.integrityVerifier(verifier))

        val attempt = controller.verifyOnce(fixture.metadata.evidenceId)

        assertTrue(attempt is VerificationAttempt.Completed)
        val completed = attempt as VerificationAttempt.Completed
        assertEquals(IntegrityStatus.VERIFIED, completed.result.status)
        assertEquals(fixture.metadata, completed.metadataBefore)
        assertEquals(fixture.metadata, completed.metadataAfter)
        assertEquals(fixture.metadata, fixture.dao.stored(fixture.metadata.evidenceId)?.toDomain())
        assertEquals(1, verifier.calls)
    }

    @Test
    fun hashMismatchIsReportedWithoutChangingDigest() = runTest {
        val tampered = fixture(sha256 = "a".repeat(64))
        val verifier = FakeNge1Verifier()
        val controller = EvidenceDetailsController(
            tampered.repository,
            tampered.integrityVerifier(verifier)
        )

        val attempt = controller.verifyOnce(tampered.metadata.evidenceId)

        assertTrue(attempt is VerificationAttempt.Completed)
        val completed = attempt as VerificationAttempt.Completed
        assertEquals(IntegrityStatus.HASH_MISMATCH, completed.result.status)
        assertEquals(0, verifier.calls)
        assertEquals("a".repeat(64), tampered.dao.stored(tampered.metadata.evidenceId)?.sha256)
    }

    @Test
    fun nge1AuthenticationFailureIsReportedExactly() = runTest {
        val fixture = fixture()
        val verifier = FakeNge1Verifier().apply {
            failure = AuthenticationFailedException("simulated authentication failure")
        }
        val controller = EvidenceDetailsController(fixture.repository, fixture.integrityVerifier(verifier))

        val attempt = controller.verifyOnce(fixture.metadata.evidenceId)

        assertTrue(attempt is VerificationAttempt.Completed)
        assertEquals(
            IntegrityStatus.NGE1_AUTHENTICATION_FAILED,
            (attempt as VerificationAttempt.Completed).result.status
        )
    }

    @Test
    fun missingFileAndMissingMetadataAreReported() = runTest {
        val missingFile = fixture(writeContainer = false)
        val missingFileController = EvidenceDetailsController(
            missingFile.repository,
            missingFile.integrityVerifier(FakeNge1Verifier())
        )
        val missingFileAttempt = missingFileController.verifyOnce(missingFile.metadata.evidenceId)
        assertEquals(
            IntegrityStatus.FILE_MISSING,
            (missingFileAttempt as VerificationAttempt.Completed).result.status
        )

        val emptyDao = FakeEvidenceMetadataDao()
        val emptyRepository = RoomEvidenceMetadataRepository(emptyDao)
        val missingMetadataController = EvidenceDetailsController(
            emptyRepository,
            EvidenceIntegrityVerifier(missingFile.filesDir, emptyRepository, FakeNge1Verifier())
        )
        val missingMetadataAttempt = missingMetadataController.verifyOnce("unknown-id")
        assertEquals(
            IntegrityStatus.METADATA_NOT_FOUND,
            (missingMetadataAttempt as VerificationAttempt.Completed).result.status
        )
    }

    @Test
    fun duplicateVerificationRequestsArePrevented() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val blockingVerifier = object : Nge1ContainerVerifier {
            override fun verify(file: File) {
                entered.complete(Unit)
                runBlocking { gate.await() }
            }
        }
        val controller = EvidenceDetailsController(
            fixture.repository,
            fixture.integrityVerifier(blockingVerifier)
        )

        val first = async { controller.verifyOnce(fixture.metadata.evidenceId) }
        entered.await()

        val duplicate = controller.verifyOnce(fixture.metadata.evidenceId)
        assertSame(VerificationAttempt.Duplicate, duplicate)

        gate.complete(Unit)
        val completed = first.await()
        assertNotNull((completed as VerificationAttempt.Completed).result)
    }

    private fun fixture(
        sha256: String? = null,
        writeContainer: Boolean = true
    ): Fixture {
        val filesDir = Files.createTempDirectory("evidence_details_controller_test").toFile()
        val relativePath = "evidence-encrypted/photos/test.ng1"
        val container = File(filesDir, relativePath)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        if (writeContainer) {
            container.parentFile?.mkdirs()
            container.writeBytes(payload)
        }
        val metadata = EvidenceMetadata(
            evidenceId = "details-test-evidence",
            mediaType = EvidenceType.PHOTO,
            originalExtension = "jpg",
            createdAtMillis = 1_700_000_000_000L,
            containerRelativePath = relativePath,
            containerSizeBytes = payload.size.toLong(),
            sha256 = sha256 ?: ContainerDigests.sha256Hex(payload)
        )
        val dao = FakeEvidenceMetadataDao()
        dao.seed(EvidenceMetadataEntity.from(metadata))
        val repository = RoomEvidenceMetadataRepository(dao)
        return Fixture(filesDir, metadata, dao, repository)
    }

    private fun Fixture.integrityVerifier(
        nge1Verifier: Nge1ContainerVerifier
    ): EvidenceIntegrityVerifier = EvidenceIntegrityVerifier(filesDir, repository, nge1Verifier)

    private data class Fixture(
        val filesDir: File,
        val metadata: EvidenceMetadata,
        val dao: FakeEvidenceMetadataDao,
        val repository: RoomEvidenceMetadataRepository
    )
}
