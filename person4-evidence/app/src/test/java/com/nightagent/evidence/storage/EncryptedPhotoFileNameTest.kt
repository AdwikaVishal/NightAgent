package com.nightagent.evidence.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for published encrypted photo names and locations. */
class EncryptedPhotoFileNameTest {

    @Test
    fun encryptedDirectoryIsAppPrivateRelative() {
        assertEquals("evidence-encrypted/photos", EncryptedPhotoFileName.ENCRYPTED_PHOTOS_DIRECTORY)

        val filesDir = File("files")
        val directory = EncryptedPhotoFileName.encryptedPhotosDirectory(filesDir)

        assertEquals(File(filesDir, "evidence-encrypted/photos").path, directory.path)
    }

    @Test
    fun namesAreDeterministicAndUniqueByUuid() {
        assertEquals("photo_123_uuid.ng1", EncryptedPhotoFileName.create(123L, "uuid"))
        assertTrue(EncryptedPhotoFileName.newName().endsWith(".ng1"))
        assertNotEquals(EncryptedPhotoFileName.newName(), EncryptedPhotoFileName.newName())
    }
}
