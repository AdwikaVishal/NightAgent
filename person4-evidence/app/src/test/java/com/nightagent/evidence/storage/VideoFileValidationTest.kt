package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Verifies the shared finalization check against video output files. */
class VideoFileValidationTest {

    private fun tempFile(): File = Files.createTempFile("video_validation_test", ".mp4").toFile()

    @Test
    fun missing_video_file_is_rejected() {
        val file = File(tempFile().parentFile, "does_not_exist_${System.nanoTime()}.mp4")

        assertEquals(MediaValidation.Missing, validateMediaFile(file))
    }

    @Test
    fun empty_video_file_is_rejected() {
        val file = tempFile()

        assertEquals(MediaValidation.Empty, validateMediaFile(file))
    }

    @Test
    fun non_empty_video_file_is_accepted_with_its_size() {
        val file = tempFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertEquals(MediaValidation.Valid(5), validateMediaFile(file))
    }
}
