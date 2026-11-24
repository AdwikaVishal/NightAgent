package com.nightagent.evidence.capture

import androidx.camera.video.VideoRecordEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Tests the gate that allows encryption only after successful finalization. */
class VideoFinalizationTest {

    @Test
    fun successfulFinalizeAuthorizesEncryption() {
        val file = validFile()

        val decision = evaluateVideoFinalization(
            error = VideoRecordEvent.Finalize.ERROR_NONE,
            eventFile = file,
            currentFile = file,
            hasActiveRecording = true
        )

        assertEquals(VideoFinalizationAction.ENCRYPT, decision.action)
        assertEquals(VideoFinalizationReason.READY, decision.reason)
        assertSame(file, decision.fileToEncrypt)
        assertNull(decision.fileToDiscard)
    }

    @Test
    fun failedFinalizeNeverAuthorizesEncryption() {
        val file = validFile()

        val decision = evaluateVideoFinalization(
            error = VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            eventFile = file,
            currentFile = file,
            hasActiveRecording = true
        )

        assertEquals(VideoFinalizationAction.DISCARD, decision.action)
        assertEquals(VideoFinalizationReason.FAILED, decision.reason)
        assertSame(file, decision.fileToDiscard)
        assertNull(decision.fileToEncrypt)
    }

    @Test
    fun invalidFinalizedFileNeverAuthorizesEncryption() {
        val file = Files.createTempFile("video_finalization_test", ".mp4").toFile()

        val decision = evaluateVideoFinalization(
            error = VideoRecordEvent.Finalize.ERROR_NONE,
            eventFile = file,
            currentFile = file,
            hasActiveRecording = true
        )

        assertEquals(VideoFinalizationAction.DISCARD, decision.action)
        assertEquals(VideoFinalizationReason.INVALID, decision.reason)
        assertSame(file, decision.fileToDiscard)
        assertNull(decision.fileToEncrypt)
    }

    @Test
    fun cancelledOrMismatchedFinalizeNeverAuthorizesEncryption() {
        val expected = validFile()
        val stale = validFile()

        val decision = evaluateVideoFinalization(
            error = VideoRecordEvent.Finalize.ERROR_NONE,
            eventFile = stale,
            currentFile = expected,
            hasActiveRecording = true
        )

        assertEquals(VideoFinalizationAction.DISCARD, decision.action)
        assertEquals(VideoFinalizationReason.CANCELLED, decision.reason)
        assertSame(stale, decision.fileToDiscard)
        assertNull(decision.fileToEncrypt)
    }

    @Test
    fun finalizeWithoutActiveRecordingNeverAuthorizesEncryption() {
        val file = validFile()

        val decision = evaluateVideoFinalization(
            error = VideoRecordEvent.Finalize.ERROR_NONE,
            eventFile = file,
            currentFile = file,
            hasActiveRecording = false
        )

        assertEquals(VideoFinalizationAction.DISCARD, decision.action)
        assertEquals(VideoFinalizationReason.CANCELLED, decision.reason)
        assertNull(decision.fileToEncrypt)
    }

    @Test
    fun finalizeWithoutEventFileNeverAuthorizesEncryption() {
        val file = validFile()

        val decision = evaluateVideoFinalization(
            error = VideoRecordEvent.Finalize.ERROR_NONE,
            eventFile = null,
            currentFile = file,
            hasActiveRecording = true
        )

        assertEquals(VideoFinalizationAction.DISCARD, decision.action)
        assertEquals(VideoFinalizationReason.CANCELLED, decision.reason)
        assertNull(decision.fileToEncrypt)
        assertNull(decision.fileToDiscard)
    }

    private fun validFile(): File =
        Files.createTempFile("video_finalization_test", ".mp4").toFile()
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
}
