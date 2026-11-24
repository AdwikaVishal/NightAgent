package com.nightagent.evidence.crypto

import android.system.ErrnoException

/**
 * One sanitized failure record for a capture operation.
 *
 * Only identifiers are stored: the media operation, the exact failing stage, the
 * exception class name and a numeric error code when one is available. Keys,
 * plaintext, private paths and raw exception messages are never captured.
 */
data class RecordedEvidenceFailure(
    val operation: String,
    val stage: String,
    val exceptionClass: String,
    val errorCode: String?
)

/**
 * A diagnostic logger that also remembers the first failure of the active
 * capture so the UI can show it without ADB.
 *
 * The pipeline logs a failure at every unwinding stage (Keystore, publisher,
 * engine, store). The FIRST failure is the deepest, most specific one, so it is
 * the one retained for the active operation. [beginOperation] resets the record
 * at the start of each capture.
 */
interface EvidenceFailureRecorder : EvidenceDiagnosticLogger {
    fun beginOperation(operation: String)

    /** Returns the recorded failure only if it belongs to [operation]. */
    fun recordedFailure(operation: String): RecordedEvidenceFailure?
}

/**
 * Records the first failure per active capture and forwards every stage/failure
 * to [delegate] (the Android logcat logger in production).
 *
 * This class is safe to instantiate in JVM unit tests: it only inspects an
 * [ErrnoException] when the reported error actually is one, which never happens
 * off-device. Captures run one at a time, so a single active record is enough.
 */
class RecordingEvidenceDiagnosticLogger(
    private val delegate: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) : EvidenceFailureRecorder {

    private val lock = Any()
    private var activeOperation: String? = null
    private var firstFailure: RecordedEvidenceFailure? = null

    override fun beginOperation(operation: String) {
        synchronized(lock) {
            activeOperation = operation
            firstFailure = null
        }
    }

    override fun stage(operation: String, stage: String) {
        delegate.stage(operation, stage)
    }

    override fun failure(operation: String, stage: String, error: Throwable) {
        delegate.failure(operation, stage, error)
        synchronized(lock) {
            val active = activeOperation
            if (active != null && firstFailure == null) {
                firstFailure = RecordedEvidenceFailure(
                    operation = active,
                    stage = stage,
                    exceptionClass = error::class.java.simpleName,
                    errorCode = errorCodeOf(error)
                )
            }
        }
    }

    override fun recordedFailure(operation: String): RecordedEvidenceFailure? =
        synchronized(lock) {
            firstFailure?.takeIf { it.operation == operation }
        }

    private fun errorCodeOf(error: Throwable): String? = when (error) {
        is ErrnoException -> "errno=${error.errno}"
        else -> null
    }
}
