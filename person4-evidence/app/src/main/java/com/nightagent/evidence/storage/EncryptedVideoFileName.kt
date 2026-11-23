package com.nightagent.evidence.storage

import java.io.File
import java.util.UUID

/** Names and locations for published, encrypted video evidence. */
object EncryptedVideoFileName {

    /** Directory relative to the standalone app's private files directory. */
    const val ENCRYPTED_VIDEOS_DIRECTORY: String = "evidence-encrypted/videos"

    fun encryptedVideosDirectory(filesDir: File): File =
        File(filesDir, ENCRYPTED_VIDEOS_DIRECTORY)

    /** Deterministic form used by tests and by [newName]. */
    fun create(timestampMillis: Long, uuid: String): String =
        "video_${timestampMillis}_${uuid}.ng1"

    /** Unique name combining recording time and a random UUID. */
    fun newName(
        timestampMillis: Long = System.currentTimeMillis(),
        uuid: String = UUID.randomUUID().toString()
    ): String = create(timestampMillis, uuid)
}
