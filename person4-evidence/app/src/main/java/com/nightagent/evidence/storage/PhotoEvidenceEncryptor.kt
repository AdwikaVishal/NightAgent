package com.nightagent.evidence.storage

import com.nightagent.evidence.crypto.EncryptionResult
import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.model.EvidenceType
import java.io.File

/**
 * Thin adapter over the Phase 4A-1 engine.
 *
 * This contains no cryptographic logic; it exists so media pipelines can be
 * unit tested with a fake encryptor while production uses the real NGE1 engine.
 */
interface EvidenceEncryptor {
    fun encrypt(plaintext: File, target: File, mediaType: EvidenceType): EncryptionResult

    /** Throws if the published container is not fully valid. */
    fun verify(encrypted: File)
}

/** Production adapter backed by the existing NGE1 engine. */
class Nge1EvidenceEncryptor(
    private val engine: EvidenceEncryptionEngine
) : EvidenceEncryptor {

    override fun encrypt(plaintext: File, target: File, mediaType: EvidenceType): EncryptionResult =
        engine.encrypt(plaintext, target, mediaType)

    override fun verify(encrypted: File) {
        engine.verify(encrypted)
    }
}

// Keep the Phase 4A-2A photo names source-compatible while the same adapter is
// now shared by photo and audio publication.
typealias PhotoEvidenceEncryptor = EvidenceEncryptor
typealias Nge1PhotoEvidenceEncryptor = Nge1EvidenceEncryptor
