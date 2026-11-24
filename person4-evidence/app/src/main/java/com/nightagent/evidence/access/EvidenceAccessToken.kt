package com.nightagent.evidence.access

import com.nightagent.evidence.model.EvidenceType
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scoped handle to one app-private temporary plaintext file.
 *
 * The path is exposed only because a viewer/player needs it, and only for the
 * lifetime of this token. Close the token (or the owning controller) as soon as
 * access is finished. [toString] deliberately omits the path so it cannot leak
 * through logs or crash reports.
 */
class EvidenceAccessToken internal constructor(
    internal val tokenId: String,
    val evidenceId: String,
    val mediaType: EvidenceType,
    val originalExtension: String,
    val plaintextFile: File,
    private val onRelease: (EvidenceAccessToken) -> Unit
) : Closeable {

    private val releasedFlag = AtomicBoolean(false)
    val isReleased: Boolean get() = releasedFlag.get()

    /** Idempotent; deletes the temporary plaintext and its private directory. */
    override fun close() {
        if (releasedFlag.compareAndSet(false, true)) onRelease(this)
    }

    override fun toString(): String =
        "EvidenceAccessToken(evidenceId=$evidenceId, released=${releasedFlag.get()})"
}
