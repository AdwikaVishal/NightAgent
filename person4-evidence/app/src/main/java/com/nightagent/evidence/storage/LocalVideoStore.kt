package com.nightagent.evidence.storage

import java.io.File
import java.io.IOException

/** App-private staging storage for video recordings (unencrypted in this phase). */
class LocalVideoStore(private val filesDir: File) {

    /**
     * Creates the video staging directory if needed and returns a unique output
     * target. The file is not created here; CameraX creates it when recording
     * starts.
     */
    @Throws(IOException::class)
    fun prepareOutput(): File {
        val directory = VideoFileName.videoDirectory(filesDir)

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create video staging directory: ${directory.path}")
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            throw IOException("Video staging directory is not writable: ${directory.path}")
        }

        return File(directory, VideoFileName.newName())
    }

    /** Removes a failed/cancelled recording so it cannot be mistaken for evidence. */
    fun deleteIncomplete(file: File) {
        runCatching {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit()
            }
        }
    }
}
