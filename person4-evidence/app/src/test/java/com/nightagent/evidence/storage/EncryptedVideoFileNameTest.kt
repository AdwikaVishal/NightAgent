package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for published encrypted video names and locations. */
class EncryptedVideoFileNameTest {

    @Test
    fun encryptedDirectoryIsAppPrivateRelative() {
        assertEquals("evidence-encrypted/videos", EncryptedVideoFileName.ENCRYPTED_VIDEOS_DIRECTORY)

        val filesDir = File("files")
        assertEquals(
            File(filesDir, "evidence-encrypted/videos").path,
            EncryptedVideoFileName.encryptedVideosDirectory(filesDir).path
        )
    }

    @Test
    fun namesUseNg1ExtensionAndAreUnique() {
        assertEquals("video_123_uuid.ng1", EncryptedVideoFileName.create(123L, "uuid"))
        assertTrue(EncryptedVideoFileName.newName().endsWith(".ng1"))
        assertNotEquals(EncryptedVideoFileName.newName(), EncryptedVideoFileName.newName())
    }
}
