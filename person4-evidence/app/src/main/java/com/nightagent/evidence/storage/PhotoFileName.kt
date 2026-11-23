package com.nightagent.evidence.storage

import java.io.File
import java.util.UUID

/**
 * Pure helpers for app-private, unencrypted photo staging.
 *
 * These files are temporary plaintext staging files. Phase 4A-2A encrypts and
 * verifies a copy into [EncryptedPhotoFileName] before the staged file is deleted.
 */
object PhotoFileName {

    /** Directory relative to Context.filesDir. */
    const val PHOTOS_DIRECTORY: String = "evidence-staging/photos"

    fun photosDirectory(filesDir: File): File = File(filesDir, PHOTOS_DIRECTORY)

    /** Deterministic form used by tests and by [newName]. */
    fun create(timestampMillis: Long, uuid: String): String =
        "photo_${timestampMillis}_${uuid}.jpg"

    /** Unique name combining capture time and a random UUID. */
    fun newName(
        timestampMillis: Long = System.currentTimeMillis(),
        uuid: String = UUID.randomUUID().toString()
    ): String = create(timestampMillis, uuid)
}
