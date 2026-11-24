package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhotoFileNameTest {

    @Test
    fun filename_contains_timestamp_and_uuid_and_uses_jpg_extension() {
        val name = PhotoFileName.create(1_700_000_000_000L, "abc-123")

        assertEquals("photo_1700000000000_abc-123.jpg", name)
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun generated_names_are_unique() {
        val names = (1..100).map { PhotoFileName.newName() }

        assertEquals(100, names.toSet().size)
        assertTrue(names.all { it.endsWith(".jpg") })
    }

    @Test
    fun photos_directory_is_inside_app_private_files_dir() {
        val filesDir = File("root", "files")

        val photosDir = PhotoFileName.photosDirectory(filesDir)

        assertEquals(File(filesDir, "evidence-staging/photos"), photosDir)
        assertTrue(photosDir.path.startsWith(filesDir.path))
        assertEquals("evidence-staging${File.separator}photos", PhotoFileName.PHOTOS_DIRECTORY.replace('/', File.separatorChar))
    }
}
