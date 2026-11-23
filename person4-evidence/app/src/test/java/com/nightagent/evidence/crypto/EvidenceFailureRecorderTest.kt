package com.nightagent.evidence.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** JVM tests for the first-failure recording used by the in-app diagnostic. */
class EvidenceFailureRecorderTest {

    private class ForwardingLogger : EvidenceDiagnosticLogger {
        val stages = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<Triple<String, String, String>>()
        override fun stage(operation: String, stage: String) {
            stages += operation to stage
        }
        override fun failure(operation: String, stage: String, error: Throwable) {
            failures += Triple(operation, stage, error::class.java.simpleName)
        }
    }

    @Test
    fun firstFailureWinsAndDeeperUnwindingDoesNotOverwrite() {
        val delegate = ForwardingLogger()
        val recorder = RecordingEvidenceDiagnosticLogger(delegate)
        recorder.beginOperation("PHOTO")

        recorder.failure("KEYSTORE", "WRAP_DATA_KEY", IllegalStateException("deep"))
        recorder.failure("PHOTO", "WRAP_DATA_KEY", IllegalStateException("deep"))
        recorder.failure("PHOTO", "ENCRYPT", IllegalStateException("outer"))
        recorder.failure("PHOTO", "STORE_ENCRYPT", IllegalStateException("store"))

        val recorded = recorder.recordedFailure("PHOTO")
        assertEquals("WRAP_DATA_KEY", recorded!!.stage)
        assertEquals("IllegalStateException", recorded.exceptionClass)
        assertEquals("PHOTO", recorded.operation)
        // Every failure is still forwarded to the delegate for logcat.
        assertEquals(4, delegate.failures.size)
    }

    @Test
    fun beginOperationResetsTheRecord() {
        val recorder = RecordingEvidenceDiagnosticLogger()
        recorder.beginOperation("PHOTO")
        recorder.failure("PHOTO", "WRAP_DATA_KEY", IOException("first"))
        assertEquals("WRAP_DATA_KEY", recorder.recordedFailure("PHOTO")!!.stage)

        recorder.beginOperation("AUDIO")
        assertNull(recorder.recordedFailure("AUDIO"))
        recorder.failure("AUDIO", "PUBLISH", IOException("second"))
        assertEquals("PUBLISH", recorder.recordedFailure("AUDIO")!!.stage)
        // The previous operation's record no longer resolves.
        assertNull(recorder.recordedFailure("PHOTO"))
    }

    @Test
    fun failuresWithoutAnActiveOperationAreNotRecorded() {
        val recorder = RecordingEvidenceDiagnosticLogger()
        recorder.failure("PHOTO", "WRAP_DATA_KEY", IOException("no active"))
        assertNull(recorder.recordedFailure("PHOTO"))
    }

    @Test
    fun recordedFailureIsScopedToItsOperation() {
        val recorder = RecordingEvidenceDiagnosticLogger()
        recorder.beginOperation("VIDEO")
        recorder.failure("PUBLISH", "PUBLISH_CREATE_EXCLUSIVE", IOException("x"))
        assertEquals("PUBLISH_CREATE_EXCLUSIVE", recorder.recordedFailure("VIDEO")!!.stage)
        assertNull(recorder.recordedFailure("PHOTO"))
        assertNull(recorder.recordedFailure("AUDIO"))
    }

    @Test
    fun nonErrnoErrorsHaveNoNumericCode() {
        val recorder = RecordingEvidenceDiagnosticLogger()
        recorder.beginOperation("PHOTO")
        recorder.failure("PHOTO", "WRAP_DATA_KEY", IOException("no errno"))
        assertNull(recorder.recordedFailure("PHOTO")!!.errorCode)
    }

    @Test
    fun stagesAreForwardedToTheDelegate() {
        val delegate = ForwardingLogger()
        val recorder = RecordingEvidenceDiagnosticLogger(delegate)
        recorder.stage("PHOTO", "STORE_START")
        recorder.stage("PHOTO", "MASTER_KEY")
        assertTrue(delegate.stages.contains("PHOTO" to "STORE_START"))
        assertTrue(delegate.stages.contains("PHOTO" to "MASTER_KEY"))
    }
}
