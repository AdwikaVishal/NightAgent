package com.nightagent.evidence.sync

/**
 * Backend boundary for uploading encrypted containers.
 *
 * Implementations must never request or receive plaintext or encryption keys.
 * [isConfigured] lets the controller report a clear not-configured result
 * instead of pretending an upload happened.
 */
interface EvidenceUploader {
    val isConfigured: Boolean
    suspend fun upload(request: EncryptedUploadRequest): UploadResult
}
