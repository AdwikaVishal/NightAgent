package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoFileNameTest {

    @Test
    fun create_uses_expected_mp4_name() {
        assertEquals("video_1234_unit-uuid.mp4", VideoFileName.create(1234L, "unit-uuid"))
    }

    @Test
    fun new_name_matches_deterministic_form() {
        assertEquals(
            VideoFileName.create(999L, "fixed-uuid"),
            VideoFileName.newName(999L, "fixed-uuid")
        )
    }

    @Test
    fun new_name_is_unique_for_same_timestamp() {
        val first = VideoFileName.newName(1234L, "first-uuid")
        val second = VideoFileName.newName(1234L, "second-uuid")

        assertNotEquals(first, second)
        assertTrue(first.endsWith(".mp4"))
    }

    @Test
    fun video_directory_is_under_evidence_staging() {
        val filesDir = File("files")

        assertEquals(
            File("files/evidence-staging/videos"),
            VideoFileName.videoDirectory(filesDir)
        )
    }
}
