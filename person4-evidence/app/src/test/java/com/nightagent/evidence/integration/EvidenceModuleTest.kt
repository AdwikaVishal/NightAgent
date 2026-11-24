package com.nightagent.evidence.integration

import com.nightagent.evidence.access.EvidenceAccessController
import com.nightagent.evidence.access.EvidenceAccessResult
import com.nightagent.evidence.consent.ConsentGate
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.metadata.RoomEvidenceMetadataRepository
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionStatus
import com.nightagent.evidence.retention.DeletionResult
import com.nightagent.evidence.retention.EvidenceRetentionController
import com.nightagent.evidence.sync.CloudUploadController
import com.nightagent.evidence.sync.NotConfiguredEvidenceUploader
import com.nightagent.evidence.sync.UploadResult
import com.nightagent.evidence.sync.UploadState
import com.nightagent.evidence.testutil.FakeConsentRepository
import com.nightagent.evidence.testutil.FakeEvidenceMetadataDao
import com.nightagent.evidence.testutil.FakeNge1Verifier
import com.nightagent.evidence.testutil.FakePermissionStateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host-facing facade keeps authorization inside the module. */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceModuleTest {

    @Test
    fun sosTriggerRequiresCaptureConsentAndPermission() = runTest {
        val fixture = fixture("module_sos")
        val trigger = MockEvidenceCaptureTrigger()
        val module = fixture.module(trigger)
        val request = CaptureTriggerRequest(EvidenceType.PHOTO, TriggerSource.SOS_MOCK)

        val denied = module.triggerCapture(request)
        assertTrue(denied is CaptureTriggerResult.Denied)
        assertEquals(
            AuthorizationResult.Reason.CONSENT_NOT_GRANTED,
            (denied as CaptureTriggerResult.Denied).reason
        )
        assertTrue(trigger.requests.isEmpty())

        fixture.consent.grant(ConsentKey.capture(EvidenceType.PHOTO))
        val missingPermission = module.triggerCapture(request)
        assertTrue(missingPermission is CaptureTriggerResult.Denied)
        assertEquals(
            AuthorizationResult.Reason.PERMISSION_MISSING,
            (missingPermission as CaptureTriggerResult.Denied).reason
        )

        fixture.permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)
        val unsupported = module.triggerCapture(request)
        assertTrue(unsupported is CaptureTriggerResult.Unsupported)
        assertEquals(1, trigger.requests.size)
        assertEquals(request, trigger.requests.single())
    }

    @Test
    fun facadeDelegatesListDetailsVerifyAccessUploadAndDelete() = runTest {
        val fixture = fixture("module_delegation")
        val module = fixture.module(MockEvidenceCaptureTrigger())

        assertEquals(IntegrityStatus.METADATA_NOT_FOUND, module.verify("missing").status)
        assertEquals(null, module.evidenceDetails("missing"))
        assertTrue(module.open("missing") is EvidenceAccessResult.Denied)
        assertTrue(module.upload("missing") is UploadResult.Denied)
        assertEquals(DeletionResult.NotFound, module.delete("missing"))
        assertEquals(0, fixture.filesDir.listFiles()?.size ?: 0)
    }

    @Test
    fun facadeManagesConsentAndExposesUploadState() = runTest {
        val fixture = fixture("module_consent")
        val module = fixture.module(MockEvidenceCaptureTrigger())
        val key = ConsentKey.CLOUD_UPLOAD

        assertEquals(UploadState.IDLE, module.uploadState.value)
        assertEquals(false, module.consentRecord(key).isGranted)

        module.grantConsent(key)
        assertEquals(true, module.observeConsent(key).first().isGranted)
        assertEquals(true, module.observeConsent().first()[key]?.isGranted)

        module.revokeConsent(key)
        assertEquals(false, module.consentRecord(key).isGranted)
    }

    @Test
    fun publicationIsAuthorizedAndReturnsVerifiableEvidenceId() = runTest {
        val fixture = fixture("module_publish")
        val module = fixture.module(MockEvidenceCaptureTrigger())
        val plaintext = File(fixture.filesDir, "captured_photo.jpg").apply {
            writeBytes(ByteArray(512) { it.toByte() })
        }

        val denied = module.publish(plaintext, EvidenceType.PHOTO)
        assertEquals(PublicationResult.Denied(AuthorizationResult.Reason.CONSENT_NOT_GRANTED), denied)
        assertTrue(plaintext.isFile)

        module.grantConsent(ConsentKey.capture(EvidenceType.PHOTO))
        fixture.permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)
        val published = module.publish(plaintext, EvidenceType.PHOTO) as PublicationResult.Published

        assertTrue(published.evidenceId.isNotBlank())
        assertNotNull(module.evidenceDetails(published.evidenceId))
        assertEquals(IntegrityStatus.VERIFIED, module.verify(published.evidenceId).status)
        assertEquals(false, plaintext.exists())

        module.grantConsent(ConsentKey.CLOUD_UPLOAD)
        assertTrue(module.upload(published.evidenceId) is UploadResult.NotConfigured)
        assertEquals(UploadState.NOT_CONFIGURED, module.uploadState.value)
    }

    @Test
    fun sosTriggerCarriesHostCorrelationId() = runTest {
        val fixture = fixture("module_sos_correlation")
        val trigger = MockEvidenceCaptureTrigger()
        val module = fixture.module(trigger)

        module.grantConsent(ConsentKey.capture(EvidenceType.AUDIO))
        fixture.permissions.set(AppPermission.RECORD_AUDIO, PermissionStatus.GRANTED)
        val request = CaptureTriggerRequest(EvidenceType.AUDIO, TriggerSource.SOS, "sos-123")

        assertTrue(module.triggerCapture(request) is CaptureTriggerResult.Unsupported)
        assertEquals(TriggerSource.SOS, trigger.requests.single().source)
        assertEquals("sos-123", trigger.requests.single().externalRequestId)
    }
    private suspend fun fixture(name: String): Fixture {
        val filesDir = Files.createTempDirectory(name).toFile()
        val dao = FakeEvidenceMetadataDao()
        val repository = RoomEvidenceMetadataRepository(dao)
        val verifier = EvidenceIntegrityVerifier(filesDir, repository, FakeNge1Verifier())
        val engine = EvidenceEncryptionEngine(
            TestKeyAccess(listOf(TestMasterKey.seeded(0x33))), JvmAtomicFilePublisher()
        )
        val access = EvidenceAccessController(
            filesDir, repository, verifier, engine, UnconfinedTestDispatcher()
        )
        val retention = EvidenceRetentionController(
            filesDir, repository, UnconfinedTestDispatcher()
        )
        val consent = FakeConsentRepository()
        val permissions = FakePermissionStateProvider()
        val authorization = EvidenceAuthorization(ConsentGate(consent), permissions)
        val upload = CloudUploadController(
            authorization, filesDir, repository, verifier, NotConfiguredEvidenceUploader()
        )
        val publication = EvidencePublicationController(filesDir, repository, engine)
        return Fixture(
            filesDir, repository, verifier, access, retention, authorization, upload,
            publication, consent, permissions
        )
    }

    private fun Fixture.module(trigger: EvidenceCaptureTrigger): EvidenceModule = EvidenceModule(
        authorization = authorization,
        consentRepository = consent,
        metadataRepository = repository,
        integrityVerifier = verifier,
        accessController = access,
        retentionController = retention,
        cloudUploadController = upload,
        publicationController = publication,
        captureTrigger = trigger
    )

    private data class Fixture(
        val filesDir: File,
        val repository: RoomEvidenceMetadataRepository,
        val verifier: EvidenceIntegrityVerifier,
        val access: EvidenceAccessController,
        val retention: EvidenceRetentionController,
        val authorization: EvidenceAuthorization,
        val upload: CloudUploadController,
        val publication: EvidencePublicationController,
        val consent: FakeConsentRepository,
        val permissions: FakePermissionStateProvider
    )
}
