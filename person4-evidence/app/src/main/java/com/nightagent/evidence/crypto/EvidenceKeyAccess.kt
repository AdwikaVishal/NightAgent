package com.nightagent.evidence.crypto

/**
 * A wrapped per-file data key. The IV is not secret; the ciphertext and tag are
 * produced by encrypting a 32-byte data key with a Keystore master key.
 */
class WrappedKey(
    val iv: ByteArray,
    val ciphertextAndTag: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is WrappedKey &&
            iv.contentEquals(other.iv) &&
            ciphertextAndTag.contentEquals(other.ciphertextAndTag)

    override fun hashCode(): Int =
        31 * iv.contentHashCode() + ciphertextAndTag.contentHashCode()
}

/** Access to one versioned master key. Raw key material is never exposed. */
interface EvidenceMasterKey {
    val version: Int

    fun wrap(dataKey: ByteArray): WrappedKey

    fun unwrap(wrapped: WrappedKey): ByteArray
}

/**
 * Abstraction over Keystore access. Production uses [AndroidEvidenceKeyManager];
 * JVM tests inject in-memory keys so no Android runtime is required.
 */
interface EvidenceKeyAccess {
    fun currentVersion(): Int

    /**
     * Key used only to create new evidence. Missing current-version keys may be
     * generated lazily on this path.
     */
    fun keyForEncryption(): EvidenceMasterKey

    /**
     * Key used to read existing evidence. This path must never generate or
     * replace a key; a missing or unavailable version fails closed.
     */
    fun keyForDecryption(version: Int): EvidenceMasterKey
}

/** Thrown when a container references a key version that cannot be resolved. */
class UnsupportedKeyVersionException(version: Int) :
    IllegalArgumentException("No evidence master key is available for version $version")
