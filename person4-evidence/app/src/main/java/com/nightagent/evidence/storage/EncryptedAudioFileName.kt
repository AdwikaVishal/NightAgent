package com.nightagent.evidence.storage

import java.io.File
import java.util.UUID

/** Names and locations for published, encrypted audio evidence. */
object EncryptedAudioFileName {

    /** Directory relative to the standalone app's private files directory. */
    const val ENCRYPTED_AUDIO_DIRECTORY: String = "evidence-encrypted/audio"

    fun encryptedAudioDirectory(filesDir: File): File =
        File(filesDir, ENCRYPTED_AUDIO_DIRECTORY)

    /** Deterministic form used by tests and by [newName]. */
    fun create(timestampMillis: Long, uuid: String): String =
        "audio_${timestampMillis}_${uuid}.ng1"

    /** Unique name combining recording time and a random UUID. */
    fun newName(
        timestampMillis: Long = System.currentTimeMillis(),
        uuid: String = UUID.randomUUID().toString()
    ): String = create(timestampMillis, uuid)
}
