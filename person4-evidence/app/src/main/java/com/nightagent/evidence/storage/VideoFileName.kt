package com.nightagent.evidence.storage

import java.io.File
import java.util.UUID

/**
 * Pure helpers for app-private, unencrypted video staging.
 *
 * These are temporary plaintext files. Phase 4A-2C publishes an encrypted,
 * verified copy to [EncryptedVideoFileName] before the staged file is deleted.
 */
object VideoFileName {

    /** Directory relative to Context.filesDir. */
    const val VIDEO_DIRECTORY: String = "evidence-staging/videos"

    fun videoDirectory(filesDir: File): File = File(filesDir, VIDEO_DIRECTORY)

    /** Deterministic form used by tests and by [newName]. */
    fun create(timestampMillis: Long, uuid: String): String =
        "video_${timestampMillis}_${uuid}.mp4"

    /** Unique name combining recording time and a random UUID. */
    fun newName(
        timestampMillis: Long = System.currentTimeMillis(),
        uuid: String = UUID.randomUUID().toString()
    ): String = create(timestampMillis, uuid)
}
