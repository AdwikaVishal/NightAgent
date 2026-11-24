package com.nightagent.evidence.storage

import androidx.camera.core.ImageCapture
import java.io.File
import java.io.IOException

/** App-private staging storage for photos captured in phase 3A. */
class LocalPhotoStore(private val filesDir: File) {

    data class PendingPhoto(
        val file: File,
        val outputOptions: ImageCapture.OutputFileOptions
    )

    /**
     * Creates the staging directory if needed and returns a unique output target
     * for ImageCapture. Throws [IOException] when storage cannot be prepared so
     * the caller can report a clear failure instead of capturing into nowhere.
     */
    @Throws(IOException::class)
    fun prepareOutput(): PendingPhoto {
        val directory = PhotoFileName.photosDirectory(filesDir)

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create photo staging directory: ${directory.path}")
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            throw IOException("Photo staging directory is not writable: ${directory.path}")
        }

        val file = File(directory, PhotoFileName.newName())
        return PendingPhoto(
            file = file,
            outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        )
    }
}
