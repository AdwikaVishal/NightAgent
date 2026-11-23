package com.nightagent.evidence.integration

import com.nightagent.evidence.consent.ConsentGate
import com.nightagent.evidence.consent.ConsentTerms
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionStatus
import com.nightagent.evidence.testutil.FakeConsentRepository
import com.nightagent.evidence.testutil.FakePermissionStateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceAuthorizationTest {

    private val consents = FakeConsentRepository()
    private val permissions = FakePermissionStateProvider()
    private val authorization = EvidenceAuthorization(ConsentGate(consents), permissions)

    private suspend fun grantAllCapture() {
        EvidenceType.entries.forEach { consents.grant(ConsentKey.capture(it)) }
    }

    @Test
    fun authorization_requires_consent_even_when_permission_is_granted() = runTest {
        permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)

        val result = authorization.authorize(EvidenceOperation.CAPTURE_PHOTO)

        assertEquals(
            AuthorizationResult.Reason.CONSENT_NOT_GRANTED,
            (result as AuthorizationResult.Denied).reason
        )
    }

    @Test
    fun photo_requires_photo_consent_and_camera_permission() = runTest {
        consents.grant(ConsentKey.capture(EvidenceType.PHOTO))

        val missingPermission = authorization.authorize(EvidenceOperation.CAPTURE_PHOTO)
        assertEquals(
            AuthorizationResult.Reason.PERMISSION_MISSING,
            (missingPermission as AuthorizationResult.Denied).reason
        )

        permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)
        assertTrue(authorization.isAuthorized(EvidenceOperation.CAPTURE_PHOTO))
    }

    @Test
    fun audio_requires_audio_consent_and_record_audio_permission() = runTest {
        consents.grant(ConsentKey.capture(EvidenceType.AUDIO))
        permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)

        val missingMicrophone = authorization.authorize(EvidenceOperation.CAPTURE_AUDIO)
        assertEquals(
            AuthorizationResult.Reason.PERMISSION_MISSING,
            (missingMicrophone as AuthorizationResult.Denied).reason
        )

        permissions.set(AppPermission.RECORD_AUDIO, PermissionStatus.GRANTED)
        assertTrue(authorization.isAuthorized(EvidenceOperation.CAPTURE_AUDIO))
    }

    @Test
    fun video_requires_both_camera_and_microphone_permissions() = runTest {
        grantAllCapture()
        permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)

        val onlyCamera = authorization.authorize(EvidenceOperation.CAPTURE_VIDEO)
        assertEquals(
            AuthorizationResult.Reason.PERMISSION_MISSING,
            (onlyCamera as AuthorizationResult.Denied).reason
        )

        permissions.set(AppPermission.RECORD_AUDIO, PermissionStatus.GRANTED)
        assertTrue(authorization.isAuthorized(EvidenceOperation.CAPTURE_VIDEO))
    }

    @Test
    fun cloud_upload_requires_only_separate_upload_consent() = runTest {
        grantAllCapture()
        permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)
        permissions.set(AppPermission.RECORD_AUDIO, PermissionStatus.GRANTED)

        val missingUploadConsent = authorization.authorize(EvidenceOperation.CLOUD_UPLOAD)
        assertEquals(
            AuthorizationResult.Reason.CONSENT_NOT_GRANTED,
            (missingUploadConsent as AuthorizationResult.Denied).reason
        )

        consents.grant(ConsentKey.CLOUD_UPLOAD)
        assertTrue(authorization.isAuthorized(EvidenceOperation.CLOUD_UPLOAD))
    }

    @Test
    fun permanently_denied_permission_is_reported_distinctly() = runTest {
        consents.grant(ConsentKey.capture(EvidenceType.PHOTO))
        permissions.set(AppPermission.CAMERA, PermissionStatus.PERMANENTLY_DENIED)

        val result = authorization.authorize(EvidenceOperation.CAPTURE_PHOTO)

        assertEquals(
            AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED,
            (result as AuthorizationResult.Denied).reason
        )
        assertFalse(authorization.isAuthorized(EvidenceOperation.CAPTURE_PHOTO))
    }

    @Test
    fun revoked_consent_blocks_even_with_all_permissions_granted() = runTest {
        consents.grant(ConsentKey.capture(EvidenceType.VIDEO))
        permissions.set(AppPermission.CAMERA, PermissionStatus.GRANTED)
        permissions.set(AppPermission.RECORD_AUDIO, PermissionStatus.GRANTED)

        consents.revoke(ConsentKey.capture(EvidenceType.VIDEO))
        val result = authorization.authorize(EvidenceOperation.CAPTURE_VIDEO)

        assertEquals(
            AuthorizationResult.Reason.CONSENT_REVOKED,
            (result as AuthorizationResult.Denied).reason
        )
    }

    @Test
    fun outdated_consent_blocks_authorization() = runTest {
        consents.put(
            ConsentRecord(
                key = ConsentKey.CLOUD_UPLOAD,
                isGranted = true,
                grantedAt = 100L,
                version = ConsentTerms.CURRENT_VERSION
            )
        )
        val outdatedAuthorization = EvidenceAuthorization(
            ConsentGate(consents, currentVersion = ConsentTerms.CURRENT_VERSION + 1),
            permissions
        )

        val result = outdatedAuthorization.authorize(EvidenceOperation.CLOUD_UPLOAD)

        assertEquals(
            AuthorizationResult.Reason.CONSENT_OUTDATED,
            (result as AuthorizationResult.Denied).reason
        )
    }
}
