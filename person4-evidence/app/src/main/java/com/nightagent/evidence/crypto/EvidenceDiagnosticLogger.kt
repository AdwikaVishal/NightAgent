package com.nightagent.evidence.crypto

/**
 * Sanitized diagnostics for the shared evidence encryption pipeline.
 *
 * Implementations must log only operation identifiers, stage names, exception
 * class names and numeric error codes. Keys, plaintext, private paths and raw
 * exception messages are never passed here.
 */
interface EvidenceDiagnosticLogger {
    fun stage(operation: String, stage: String)

    fun failure(operation: String, stage: String, error: Throwable)
}

/** JVM/test default. Production installs [AndroidEvidenceDiagnosticLogger]. */
object SilentEvidenceDiagnosticLogger : EvidenceDiagnosticLogger {
    override fun stage(operation: String, stage: String) = Unit

    override fun failure(operation: String, stage: String, error: Throwable) = Unit
}
