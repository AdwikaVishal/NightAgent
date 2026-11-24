package com.nightagent.evidence.integrity

import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import java.io.File

/** Adapter around the existing NGE1 verification path. */
fun interface Nge1ContainerVerifier {
    /** Throws when the container cannot be authenticated or read. */
    fun verify(file: File)
}

/** Uses the unchanged engine; no cryptographic logic is duplicated here. */
class EngineNge1ContainerVerifier(
    private val engine: EvidenceEncryptionEngine
) : Nge1ContainerVerifier {
    override fun verify(file: File) {
        engine.verify(file)
    }
}
