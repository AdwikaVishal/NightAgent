package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalVideoStoreTest {

    private fun tempFilesDir(): File = Files.createTempDirectory("video_store_test").toFile()

    @Test
    fun prepare_output_creates_private_directory_and_unique_targets() {
        val filesDir = tempFilesDir()
        val store = LocalVideoStore(filesDir)

        val first = store.prepareOutput()
        val second = store.prepareOutput()

        assertEquals(VideoFileName.videoDirectory(filesDir), first.parentFile)
        assertTrue(first.parentFile?.isDirectory == true)
        assertTrue(first.name.endsWith(".mp4"))
        assertNotEquals(first.name, second.name)
        assertFalse(first.exists())
    }

    @Test
    fun delete_incomplete_removes_partial_recording() {
        val store = LocalVideoStore(tempFilesDir())
        val file = store.prepareOutput()
        file.createNewFile()
        assertTrue(file.exists())

        store.deleteIncomplete(file)

        assertFalse(file.exists())
    }
}
