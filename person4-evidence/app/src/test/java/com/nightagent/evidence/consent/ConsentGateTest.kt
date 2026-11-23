package com.nightagent.evidence.consent

import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.testutil.FakeConsentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentGateTest {

    @Test
    fun gate_rejects_all_operations_when_no_consent_exists() = runTest {
        val gate = ConsentGate(FakeConsentRepository())

        EvidenceOperation.entries.forEach { operation ->
            val result = gate.check(operation)
            assertTrue(result is ConsentResult.Denied)
            assertEquals(
                ConsentResult.Reason.NOT_GRANTED,
                (result as ConsentResult.Denied).reason
            )
        }
        assertFalse(gate.hasAllCaptureConsents())
    }

    @Test
    fun gate_accepts_valid_consent_for_each_independent_operation() = runTest {
        val repository = FakeConsentRepository()
        val gate = ConsentGate(repository)

        repository.grant(ConsentKey.capture(EvidenceType.PHOTO))
        repository.grant(ConsentKey.capture(EvidenceType.AUDIO))
        repository.grant(ConsentKey.capture(EvidenceType.VIDEO))
        repository.grant(ConsentKey.CLOUD_UPLOAD)

        assertTrue(gate.isPhotoCaptureGranted())
        assertTrue(gate.isAudioCaptureGranted())
        assertTrue(gate.isVideoCaptureGranted())
        assertTrue(gate.isCloudUploadGranted())
        assertTrue(gate.hasAllCaptureConsents())
        EvidenceOperation.entries.forEach {
            assertTrue(gate.check(it) is ConsentResult.Granted)
        }
    }

    @Test
    fun gate_rejects_revoked_consent() = runTest {
        val repository = FakeConsentRepository()
        val gate = ConsentGate(repository)
        val key = ConsentKey.capture(EvidenceType.AUDIO)

        repository.grant(key)
        assertTrue(gate.isGranted(key))

        repository.revoke(key)
        val result = gate.check(key)

        assertEquals(
            ConsentResult.Reason.REVOKED,
            (result as ConsentResult.Denied).reason
        )
    }

    @Test
    fun gate_rejects_outdated_consent_version() = runTest {
        val repository = FakeConsentRepository()
        val key = ConsentKey.CLOUD_UPLOAD
        repository.put(
            ConsentRecord(
                key = key,
                isGranted = true,
                grantedAt = 100L,
                version = ConsentTerms.CURRENT_VERSION
            )
        )
        val gate = ConsentGate(repository, currentVersion = ConsentTerms.CURRENT_VERSION + 1)

        val result = gate.check(EvidenceOperation.CLOUD_UPLOAD)

        assertEquals(
            ConsentResult.Reason.OUTDATED_VERSION,
            (result as ConsentResult.Denied).reason
        )
        assertFalse(gate.isCloudUploadGranted())
    }

    @Test
    fun capture_consent_does_not_authorize_cloud_upload() = runTest {
        val repository = FakeConsentRepository()
        val gate = ConsentGate(repository)

        repository.grant(ConsentKey.capture(EvidenceType.PHOTO))

        assertTrue(gate.isPhotoCaptureGranted())
        assertFalse(gate.isCloudUploadGranted())
        assertEquals(
            ConsentResult.Reason.NOT_GRANTED,
            (gate.check(EvidenceOperation.CLOUD_UPLOAD) as ConsentResult.Denied).reason
        )
    }
}
