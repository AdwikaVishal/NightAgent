package com.nightagent.evidence.sync

/**
 * Safe default until a correctly configured cloud backend exists.
 *
 * The standalone evidence app has a different application id from the root
 * NightAgent Firebase app, so its google-services configuration cannot be
 * copied safely. This implementation makes that absence explicit.
 */
class NotConfiguredEvidenceUploader : EvidenceUploader {

    override val isConfigured: Boolean = false

    override suspend fun upload(request: EncryptedUploadRequest): UploadResult =
        UploadResult.NotConfigured(REASON)

    companion object {
        const val REASON: String =
            "No cloud backend configured for this application id; no upload was attempted"
    }
}
