package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioFileNameTest {

    @Test
    fun filename_contains_timestamp_and_uuid_and_uses_m4a_extension() {
        val name = AudioFileName.create(1_700_000_000_000L, "abc-123")

        assertEquals("audio_1700000000000_abc-123.m4a", name)
        assertTrue(name.endsWith(".m4a"))
    }

    @Test
    fun generated_names_are_unique() {
        val names = (1..100).map { AudioFileName.newName() }

        assertEquals(100, names.toSet().size)
        assertTrue(names.all { it.endsWith(".m4a") })
    }

    @Test
    fun audio_directory_is_inside_app_private_files_dir() {
        val filesDir = File("root", "files")

        val audioDir = AudioFileName.audioDirectory(filesDir)

        assertEquals(File(filesDir, "evidence-staging/audio"), audioDir)
        assertTrue(audioDir.path.startsWith(filesDir.path))
        assertEquals(
            "evidence-staging${File.separator}audio",
            AudioFileName.AUDIO_DIRECTORY.replace('/', File.separatorChar)
        )
    }
}
