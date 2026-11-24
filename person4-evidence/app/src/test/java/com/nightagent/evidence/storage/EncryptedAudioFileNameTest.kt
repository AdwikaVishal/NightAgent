package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for published encrypted audio names and locations. */
class EncryptedAudioFileNameTest {

    @Test
    fun encryptedDirectoryIsAppPrivateRelative() {
        assertEquals("evidence-encrypted/audio", EncryptedAudioFileName.ENCRYPTED_AUDIO_DIRECTORY)

        val filesDir = File("files")
        assertEquals(
            File(filesDir, "evidence-encrypted/audio").path,
            EncryptedAudioFileName.encryptedAudioDirectory(filesDir).path
        )
    }

    @Test
    fun namesUseNg1ExtensionAndAreUnique() {
        assertEquals(
            "audio_123_uuid.ng1",
            EncryptedAudioFileName.create(123L, "uuid")
        )
        assertTrue(EncryptedAudioFileName.newName().endsWith(".ng1"))
        assertNotEquals(EncryptedAudioFileName.newName(), EncryptedAudioFileName.newName())
    }
}
