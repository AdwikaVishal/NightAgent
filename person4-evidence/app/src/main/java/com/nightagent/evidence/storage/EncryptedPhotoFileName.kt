package com.nightagent.evidence.storage

import java.io.File
import java.util.UUID

/** Names and locations for published, encrypted photo evidence. */
object EncryptedPhotoFileName {

    /** Directory relative to the standalone app's private files directory. */
    const val ENCRYPTED_PHOTOS_DIRECTORY: String = "evidence-encrypted/photos"

    fun encryptedPhotosDirectory(filesDir: File): File =
        File(filesDir, ENCRYPTED_PHOTOS_DIRECTORY)

    /** Deterministic form used by tests and by [newName]. */
    fun create(timestampMillis: Long, uuid: String): String =
        "photo_${timestampMillis}_${uuid}.ng1"

    /** Unique name combining capture time and a random UUID. */
    fun newName(
        timestampMillis: Long = System.currentTimeMillis(),
        uuid: String = UUID.randomUUID().toString()
    ): String = create(timestampMillis, uuid)
}
