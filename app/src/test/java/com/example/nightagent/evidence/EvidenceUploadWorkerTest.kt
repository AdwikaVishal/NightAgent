package com.example.nightagent.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Verifies that every file produced by the canonical capture pipeline has an
 * allowed Storage content type, including the JPEG photos produced by
 * CameraCaptureManager.
 */
class EvidenceUploadWorkerTest {

    @Test
    fun canonicalEvidenceExtensionsMapToAllowedContentTypes() {
        val cases = mapOf(
            "chunk.m4a" to "audio/m4a",
            "chunk.aac" to "audio/aac",
            "clip.mp4" to "video/mp4",
            "clip.mov" to "video/quicktime",
            "clip.avi" to "video/x-msvideo",
            "photo.jpg" to "image/jpeg",
            "photo.jpeg" to "image/jpeg",
            "photo.png" to "image/png"
        )
        cases.forEach { (name, expected) ->
            assertEquals(expected, EvidenceUploadWorker.contentTypeForFile(File(name)))
        }
    }

    @Test
    fun unsupportedExtensionsAreRejectedInsteadOfUsingWildcardType() {
        assertNull(EvidenceUploadWorker.contentTypeForFile(File("notes.txt")))
        assertNull(EvidenceUploadWorker.contentTypeForFile(File("archive.zip")))
    }
}
