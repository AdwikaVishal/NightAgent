package com.nightagent.evidence.crypto

import android.system.ErrnoException
import android.util.Log

/** Android logcat implementation of [EvidenceDiagnosticLogger]. */
class AndroidEvidenceDiagnosticLogger : EvidenceDiagnosticLogger {

    override fun stage(operation: String, stage: String) {
        Log.d(TAG, "$operation | $stage")
    }

    override fun failure(operation: String, stage: String, error: Throwable) {
        val code = when (error) {
            is ErrnoException -> "errno=${error.errno}"
            else -> "code=-"
        }
        Log.e(TAG, "$operation | $stage | ${error::class.java.simpleName} | $code")
    }

    private companion object {
        const val TAG = "EvidenceEncryption"
    }
}
