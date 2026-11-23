package com.nightagent.evidence.capture

import androidx.camera.video.VideoRecordEvent
import com.nightagent.evidence.storage.MediaValidation
import com.nightagent.evidence.storage.validateMediaFile
import java.io.File

/** What the screen should do when CameraX reports that a recording finalized. */
enum class VideoFinalizationAction {
    ENCRYPT,
    DISCARD,
    IGNORE
}

/** Distinguishes a recorder failure from an invalid file or a cancelled recording. */
enum class VideoFinalizationReason {
    READY,
    FAILED,
    INVALID,
    CANCELLED
}

data class VideoFinalizationDecision(
    val action: VideoFinalizationAction,
    val reason: VideoFinalizationReason,
    val fileToEncrypt: File? = null,
    val fileToDiscard: File? = null
)

/**
 * Pure decision logic for the sole CameraX finalization callback.
 *
 * Encryption is authorized only for a matching, active recording that finalized
 * with ERROR_NONE and passed the existing file validation. Everything else is
 * discarded through the existing incomplete-file path and never encrypted.
 */
fun evaluateVideoFinalization(
    error: Int,
    eventFile: File?,
    currentFile: File?,
    hasActiveRecording: Boolean,
    validate: (File) -> MediaValidation = ::validateMediaFile
): VideoFinalizationDecision {
    if (!hasActiveRecording || currentFile == null || eventFile == null || eventFile != currentFile) {
        return VideoFinalizationDecision(
            action = VideoFinalizationAction.DISCARD,
            reason = VideoFinalizationReason.CANCELLED,
            fileToDiscard = eventFile
        )
    }

    if (error != VideoRecordEvent.Finalize.ERROR_NONE) {
        return VideoFinalizationDecision(
            action = VideoFinalizationAction.DISCARD,
            reason = VideoFinalizationReason.FAILED,
            fileToDiscard = eventFile
        )
    }

    return when (validate(eventFile)) {
        is MediaValidation.Valid -> VideoFinalizationDecision(
            action = VideoFinalizationAction.ENCRYPT,
            reason = VideoFinalizationReason.READY,
            fileToEncrypt = eventFile
        )

        MediaValidation.Missing, MediaValidation.Empty -> VideoFinalizationDecision(
            action = VideoFinalizationAction.DISCARD,
            reason = VideoFinalizationReason.INVALID,
            fileToDiscard = eventFile
        )
    }
}
