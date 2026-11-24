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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CombinedEvidenceAuthorizationTest {

    private val consents = FakeConsentRepository()
    private val permissions = FakePermissionStateProvider(
        mapOf(
            AppPermission.CAMERA to PermissionStatus.GRANTED,
            AppPermission.RECORD_AUDIO to PermissionStatus.GRANTED
        )
    )
    private val combined = CombinedEvidenceAuthorization(
        EvidenceAuthorization(ConsentGate(consents), permissions)
    )

    private suspend fun grantBoth() {
        consents.grant(ConsentKey.capture(EvidenceType.VIDEO))
        consents.grant(ConsentKey.capture(EvidenceType.AUDIO))
    }

    @Test
    fun both_video_and_audio_consent_with_both_permissions_is_authorized() = runTest {
        grantBoth()

        assertEquals(
            AuthorizationResult.Authorized,
            combined.authorizeVideoWithAudio()
        )
    }

    @Test
    fun missing_video_consent_is_denied() = runTest {
        consents.grant(ConsentKey.capture(EvidenceType.AUDIO))

        assertEquals(
            AuthorizationResult.Denied(
                EvidenceOperation.CAPTURE_VIDEO,
                AuthorizationResult.Reason.CONSENT_NOT_GRANTED
            ),
            combined.authorizeVideoWithAudio()
        )
    }

    @Test
    fun missing_audio_consent_is_denied_even_when_video_consent_exists() = runTest {
        consents.grant(ConsentKey.capture(EvidenceType.VIDEO))

        assertEquals(
            AuthorizationResult.Denied(
                EvidenceOperation.CAPTURE_AUDIO,
                AuthorizationResult.Reason.CONSENT_NOT_GRANTED
            ),
            combined.authorizeVideoWithAudio()
        )
    }

    @Test
    fun revoked_audio_consent_is_denied() = runTest {
        grantBoth()
        consents.revoke(ConsentKey.capture(EvidenceType.AUDIO))

        assertEquals(
            AuthorizationResult.Denied(
                EvidenceOperation.CAPTURE_AUDIO,
                AuthorizationResult.Reason.CONSENT_REVOKED
            ),
            combined.authorizeVideoWithAudio()
        )
    }

    @Test
    fun outdated_video_consent_is_denied() = runTest {
        consents.put(
            ConsentRecord(
                key = ConsentKey.capture(EvidenceType.VIDEO),
                isGranted = true,
                version = ConsentTerms.CURRENT_VERSION - 1
            )
        )
        consents.grant(ConsentKey.capture(EvidenceType.AUDIO))

        assertEquals(
            AuthorizationResult.Denied(
                EvidenceOperation.CAPTURE_VIDEO,
                AuthorizationResult.Reason.CONSENT_OUTDATED
            ),
            combined.authorizeVideoWithAudio()
        )
    }

    @Test
    fun missing_camera_permission_is_denied() = runTest {
        grantBoth()
        permissions.set(AppPermission.CAMERA, PermissionStatus.DENIED)

        assertEquals(
            AuthorizationResult.Denied(
                EvidenceOperation.CAPTURE_VIDEO,
                AuthorizationResult.Reason.PERMISSION_MISSING
            ),
            combined.authorizeVideoWithAudio()
        )
    }

    @Test
    fun permanently_denied_microphone_permission_is_denied() = runTest {
        grantBoth()
        permissions.set(
            AppPermission.RECORD_AUDIO,
            PermissionStatus.PERMANENTLY_DENIED
        )

        assertEquals(
            AuthorizationResult.Denied(
                EvidenceOperation.CAPTURE_VIDEO,
                AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED
            ),
            combined.authorizeVideoWithAudio()
        )
    }
}
