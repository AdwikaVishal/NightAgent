package com.nightagent.evidence.sync

import com.nightagent.evidence.consent.ConsentGate
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integration.AuthorizationResult
import com.nightagent.evidence.integration.EvidenceAuthorization
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.MetadataRegistrationResult
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeConsentRepository
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeNge1Verifier
import com.nightagent.evidence.testutil.FakePermissionStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Consent-at-upload and encrypted-only boundary coverage. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudUploadControllerTest {

    @Test
    fun uploadConsentIsRequiredAtTheMomentOfUpload() = runTest {
        val fixture = fixture("upload_consent")
        fixture.consent.grant(ConsentKey.CLOUD_UPLOAD)
        val uploader = FakeUploader()
        val controller = fixture.controller(uploader)

        val first = controller.upload(fixture.metadata.evidenceId)
        assertTrue(first is UploadResult.Uploaded)
        assertEquals(1, uploader.requests.size)
        assertEquals(UploadState.SUCCESS, controller.state.first())

        fixture.consent.revoke(ConsentKey.CLOUD_UPLOAD)
        val second = controller.upload(fixture.metadata.evidenceId)

        assertTrue(second is UploadResult.Denied)
        assertEquals(
            AuthorizationResult.Reason.CONSENT_REVOKED,
            (second as UploadResult.Denied).reason
        )
        assertEquals(1, uploader.requests.size)
        assertEquals(UploadState.DENIED, controller.state.first())
    }

    @Test
    fun captureConsentAloneDoesNotAuthorizeUpload() = runTest {
        val fixture = fixture("upload_capture_consent_only")
        fixture.consent.grant(ConsentKey.capture(EvidenceType.PHOTO))
        val uploader = FakeUploader()

        val result = fixture.controller(uploader).upload(fixture.metadata.evidenceId)

        assertTrue(result is UploadResult.Denied)
        assertEquals(
            AuthorizationResult.Reason.CONSENT_NOT_GRANTED,
            (result as UploadResult.Denied).reason
        )
        assertEquals(0, uploader.requests.size)
    }

    @Test
    fun notConfiguredBackendNeverReportsSuccess() = runTest {
        val fixture = fixture("upload_not_configured")
        fixture.consent.grant(ConsentKey.CLOUD_UPLOAD)
        val controller = fixture.controller(NotConfiguredEvidenceUploader())

        val result = controller.upload(fixture.metadata.evidenceId)

        assertTrue(result is UploadResult.NotConfigured)
        assertTrue((result as UploadResult.NotConfigured).reason.isNotBlank())
        assertEquals(UploadState.NOT_CONFIGURED, controller.state.first())
    }

    @Test
    fun integrityFailureBlocksUploadBeforeBackendCall() = runTest {
        val fixture = fixture("upload_integrity")
        fixture.consent.grant(ConsentKey.CLOUD_UPLOAD)
        fixture.container.writeBytes(
            fixture.container.readBytes().also { it[1] = (it[1].toInt() xor 0xFF).toByte() }
        )
        val uploader = FakeUploader()

        val result = fixture.controller(uploader).upload(fixture.metadata.evidenceId)

        assertTrue(result is UploadResult.IntegrityFailed)
        assertEquals(0, uploader.requests.size)
    }

    @Test
    fun backendFailureIsExplicitAndRetryable() = runTest {
        val fixture = fixture("upload_retry")
        fixture.consent.grant(ConsentKey.CLOUD_UPLOAD)
        val uploader = FakeUploader(
            results = mutableListOf(
                UploadResult.Failed(UploadStage.UPLOAD, IllegalStateException("network")),
                UploadResult.Uploaded(fixture.metadata.evidenceId, "remote/1")
            )
        )
        val controller = fixture.controller(uploader)

        val failed = controller.upload(fixture.metadata.evidenceId)
        assertTrue(failed is UploadResult.Failed)
        assertEquals(UploadState.FAILED, controller.state.first())

        controller.reset()
        val retried = controller.upload(fixture.metadata.evidenceId)
        assertTrue(retried is UploadResult.Uploaded)
        assertEquals(UploadState.SUCCESS, controller.state.first())
        assertEquals(2, uploader.requests.size)
    }

    @Test
    fun cancellationIsReportedWithoutSimulatedSuccess() = runTest {
        val fixture = fixture("upload_cancel")
        fixture.consent.grant(ConsentKey.CLOUD_UPLOAD)
        val uploader = FakeUploader(cancellationError = true)
        val controller = fixture.controller(uploader)

        val result = controller.upload(fixture.metadata.evidenceId)

        assertEquals(UploadResult.Cancelled, result)
        assertEquals(UploadState.CANCELLED, controller.state.first())
    }

    @Test
    fun uploaderReceivesOnlyEncryptedContainerMetadata() = runTest {
        val fixture = fixture("upload_payload")
        fixture.consent.grant(ConsentKey.CLOUD_UPLOAD)
        val uploader = FakeUploader()
        fixture.controller(uploader).upload(fixture.metadata.evidenceId)

        val request = uploader.requests.single()
        assertTrue(request.container.name.endsWith(".ng1"))
        assertEquals(fixture.metadata.sha256, request.sha256)
        assertEquals(fixture.metadata.containerSizeBytes, request.containerSizeBytes)
        assertEquals(EvidenceType.PHOTO, request.mediaType)
        assertFalse(request.toString().contains("capture.jpg"))
        assertEquals(fixture.container.canonicalPath, request.container.canonicalPath)
    }

    private suspend fun fixture(name: String): Fixture {
        val filesDir = Files.createTempDirectory(name).toFile()
        val relativePath = "evidence-encrypted/photos/$name.ng1"
        val container = File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(256) { it.toByte() })
        }
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
        val verifier = EvidenceIntegrityVerifier(filesDir, repository, FakeNge1Verifier())
        val consent = FakeConsentRepository()
        val authorization = EvidenceAuthorization(ConsentGate(consent), FakePermissionStateProvider())
        return Fixture(filesDir, container, metadata, repository, verifier, consent, authorization)
    }

    private fun Fixture.controller(uploader: EvidenceUploader): CloudUploadController =
        CloudUploadController(authorization, filesDir, repository, verifier, uploader)

    private class FakeUploader(
        override var isConfigured: Boolean = true,
        results: MutableList<UploadResult> = mutableListOf(),
        cancellationError: Boolean = false
    ) : EvidenceUploader {

        private val results = results
        private val cancellationError = cancellationError
        val requests = mutableListOf<EncryptedUploadRequest>()

        override suspend fun upload(request: EncryptedUploadRequest): UploadResult {
            requests += request
            if (cancellationError) throw CancellationException("simulated upload cancellation")
            return if (results.isNotEmpty()) {
                results.removeAt(0)
            } else {
                UploadResult.Uploaded(request.evidenceId, "remote/${request.evidenceId}")
            }
        }
    }

    private data class Fixture(
        val filesDir: File,
        val container: File,
        val metadata: EvidenceMetadata,
        val repository: RoomEvidenceMetadataRepository,
        val verifier: EvidenceIntegrityVerifier,
        val consent: FakeConsentRepository,
        val authorization: EvidenceAuthorization
    )
}
