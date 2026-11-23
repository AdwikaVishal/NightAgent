package com.nightagent.evidence.consent

import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.model.EvidenceType

/**
 * Central authorization component for application consent only.
 *
 * Android permissions are checked separately by the authorization layer.
 * This class is deliberately free of Android UI/framework dependencies so its
 * decisions can be unit tested in isolation.
 */
class ConsentGate(
    private val repository: ConsentRepository,
    private val currentVersion: Int = ConsentTerms.CURRENT_VERSION
) {

    suspend fun check(key: ConsentKey): ConsentResult = evaluate(repository.get(key), currentVersion)

    suspend fun isGranted(key: ConsentKey): Boolean = check(key) is ConsentResult.Granted

    suspend fun check(operation: EvidenceOperation): ConsentResult = check(operation.consentKey)

    suspend fun isPhotoCaptureGranted(): Boolean =
        isGranted(ConsentKey.capture(EvidenceType.PHOTO))

    suspend fun isAudioCaptureGranted(): Boolean =
        isGranted(ConsentKey.capture(EvidenceType.AUDIO))

    suspend fun isVideoCaptureGranted(): Boolean =
        isGranted(ConsentKey.capture(EvidenceType.VIDEO))

    suspend fun isCloudUploadGranted(): Boolean = isGranted(ConsentKey.CLOUD_UPLOAD)

    /** True only when all three capture decisions are currently valid. */
    suspend fun hasAllCaptureConsents(): Boolean =
        isPhotoCaptureGranted() && isAudioCaptureGranted() && isVideoCaptureGranted()

    companion object {
        internal fun evaluate(record: ConsentRecord, currentVersion: Int): ConsentResult =
            when {
                !record.isGranted && record.revokedAt != null ->
                    ConsentResult.Denied(record.key, ConsentResult.Reason.REVOKED)

                !record.isGranted ->
                    ConsentResult.Denied(record.key, ConsentResult.Reason.NOT_GRANTED)

                record.version != currentVersion ->
                    ConsentResult.Denied(record.key, ConsentResult.Reason.OUTDATED_VERSION)

                else -> ConsentResult.Granted
            }
    }
}
