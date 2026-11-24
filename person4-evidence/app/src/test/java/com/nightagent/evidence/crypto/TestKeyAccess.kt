package com.nightagent.evidence.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JVM test key. It never leaves test source and has no Android dependencies. */
class TestMasterKey(
    override val version: Int,
    seed: ByteArray
) : EvidenceMasterKey {

    init {
        require(seed.size == 32) { "Test AES keys must be 32 bytes" }
    }

    private val key = SecretKeySpec(seed.copyOf(), "AES")
    private val secureRandom = SecureRandom()

    override fun wrap(dataKey: ByteArray): WrappedKey {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return WrappedKey(iv, cipher.doFinal(dataKey))
    }

    override fun unwrap(wrapped: WrappedKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped.iv))
        }
        return cipher.doFinal(wrapped.ciphertextAndTag)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        fun seeded(seed: Int, version: Int = 1): TestMasterKey = TestMasterKey(
            version = version,
            seed = ByteArray(32) { (seed + it * 7).toByte() }
        )
    }
}

/** Simulates a Keystore alias that exists but can no longer authenticate. */
class InvalidatedTestMasterKey(override val version: Int) : EvidenceMasterKey {
    override fun wrap(dataKey: ByteArray): WrappedKey =
        throw IllegalStateException("Invalidated key must not be used for encryption")

    override fun unwrap(wrapped: WrappedKey): ByteArray =
        throw AEADBadTagException("Simulated invalidated key")
}

/**
 * Injected key source for JVM tests.
 *
 * [encryptionKey] simulates lazy current-version creation: it is available to
 * encryption even when no decryption keys are installed. Decryption has no lazy
 * path, matching the production fail-closed behavior.
 */
class TestKeyAccess(
    keys: Collection<EvidenceMasterKey>,
    private val currentVersion: Int = 1,
    private val encryptionKey: EvidenceMasterKey? = null
) : EvidenceKeyAccess {

    private val keys = keys.associateBy { it.version }

    override fun currentVersion(): Int = currentVersion

    override fun keyForEncryption(): EvidenceMasterKey =
        encryptionKey ?: keys[currentVersion] ?: throw UnsupportedKeyVersionException(currentVersion)

    override fun keyForDecryption(version: Int): EvidenceMasterKey =
        keys[version] ?: throw UnsupportedKeyVersionException(version)
}
