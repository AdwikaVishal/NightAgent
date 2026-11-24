package com.nightagent.evidence.storage

import java.io.File
import java.util.UUID

/**
 * Pure helpers for app-private, unencrypted audio staging.
 *
 * These are temporary plaintext files. Phase 4A-2B publishes an encrypted,
 * verified copy to [EncryptedAudioFileName] before the staged file is deleted.
 */
object AudioFileName {

    /** Directory relative to Context.filesDir. */
    const val AUDIO_DIRECTORY: String = "evidence-staging/audio"

    fun audioDirectory(filesDir: File): File = File(filesDir, AUDIO_DIRECTORY)

    /** Deterministic form used by tests and by [newName]. */
    fun create(timestampMillis: Long, uuid: String): String =
        "audio_${timestampMillis}_${uuid}.m4a"

    /** Unique name combining recording time and a random UUID. */
    fun newName(
        timestampMillis: Long = System.currentTimeMillis(),
        uuid: String = UUID.randomUUID().toString()
    ): String = create(timestampMillis, uuid)
}
