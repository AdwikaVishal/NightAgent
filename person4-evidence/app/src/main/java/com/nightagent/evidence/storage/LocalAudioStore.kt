package com.nightagent.evidence.storage

import java.io.File
import java.io.IOException

/** App-private staging storage for audio recordings (unencrypted in this phase). */
class LocalAudioStore(private val filesDir: File) {

    /**
     * Creates the audio staging directory if needed and returns a unique output
     * target. The file is not created here; MediaRecorder creates it on start.
     */
    @Throws(IOException::class)
    fun prepareOutput(): File {
        val directory = AudioFileName.audioDirectory(filesDir)

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create audio staging directory: ${directory.path}")
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            throw IOException("Audio staging directory is not writable: ${directory.path}")
        }

        return File(directory, AudioFileName.newName())
    }

    /** Removes a failed/incomplete recording so it cannot be mistaken for evidence. */
    fun deleteIncomplete(file: File) {
        runCatching {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit()
            }
        }
    }
}
