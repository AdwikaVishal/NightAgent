package com.nightagent.evidence.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/** Result of a stop attempt; a failed stop means the file must be discarded. */
data class StopOutcome(
    val stoppedCleanly: Boolean,
    val errorMessage: String? = null
)

/**
 * Thin lifecycle-aware wrapper around MediaRecorder.
 *
 * Only one recording can be active at a time. Every path that ends a recording
 * releases the underlying MediaRecorder, including failures and screen disposal.
 */
class AudioRecorderController(private val context: Context) {

    /** Invoked on the main thread if MediaRecorder reports an error. */
    var onError: ((String) -> Unit)? = null

    private var recorder: MediaRecorder? = null

    val isRecording: Boolean get() = recorder != null

    fun start(file: File) {
        check(recorder == null) { "A recording is already in progress." }

        val candidate = createRecorder()
        try {
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setOutputFile(file.absolutePath)
            candidate.setOnErrorListener { _, what, extra ->
                val message = "Microphone error ($what, $extra)"
                Handler(Looper.getMainLooper()).post { onError?.invoke(message) }
            }
            candidate.prepare()
            candidate.start()
            recorder = candidate
        } catch (e: Exception) {
            safeRelease(candidate)
            throw e
        }
    }

    /** Stops and releases the active recorder. Never throws from stop/release. */
    fun stop(): StopOutcome {
        val active = recorder ?: return StopOutcome(false, "No active recording.")
        recorder = null

        var error: String? = null
        try {
            active.stop()
        } catch (e: RuntimeException) {
            error = e.message ?: "MediaRecorder.stop() failed."
        }
        safeRelease(active)
        return StopOutcome(stoppedCleanly = error == null, errorMessage = error)
    }

    /** Used when leaving the screen: stops and releases without reporting success. */
    fun release() {
        val active = recorder ?: return
        recorder = null
        try {
            active.stop()
        } catch (e: RuntimeException) {
            // A recording that was never started can throw here; disposal must not crash.
        }
        safeRelease(active)
    }

    private fun safeRelease(mediaRecorder: MediaRecorder) {
        try {
            mediaRecorder.reset()
        } catch (e: RuntimeException) {
            // Ignore: release is still attempted below.
        }
        try {
            mediaRecorder.release()
        } catch (e: RuntimeException) {
            // Nothing further can be done; resources are best-effort released.
        }
    }

    private fun createRecorder(): MediaRecorder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return MediaRecorder(context)
        }
        return createLegacyRecorder()
    }

    @Suppress("DEPRECATION")
    private fun createLegacyRecorder(): MediaRecorder = MediaRecorder()
}
