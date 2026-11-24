package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioFileValidationTest {

    private fun tempFile(): File = Files.createTempFile("audio_validation_test", ".m4a").toFile()

    @Test
    fun missing_file_is_rejected() {
        val file = File(tempFile().parentFile, "does_not_exist_${System.nanoTime()}.m4a")

        assertEquals(MediaValidation.Missing, validateMediaFile(file))
    }

    @Test
    fun empty_file_is_rejected() {
        val file = tempFile()

        assertEquals(MediaValidation.Empty, validateMediaFile(file))
    }

    @Test
    fun non_empty_file_is_accepted_with_its_size() {
        val file = tempFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertEquals(MediaValidation.Valid(4), validateMediaFile(file))
    }
}
