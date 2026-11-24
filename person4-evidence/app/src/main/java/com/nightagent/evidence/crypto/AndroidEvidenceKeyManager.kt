package com.nightagent.evidence.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore implementation of [EvidenceKeyAccess].
 *
 * Master keys are non-exportable AES-256-GCM keys. They are generated lazily and
 * addressed by versioned aliases so older evidence remains decryptable. This
 * class never returns or persists raw master key material.
 *
 * Provider note: the provider named "AndroidKeyStore" owns the key store and the
 * key generator, but it registers NO Cipher algorithms. The AES/GCM cipher for
 * Keystore keys is registered by AndroidKeyStoreBCWorkaroundProvider and is found
 * only when the JCA is allowed to search all providers. See [KeystoreCipherFactory].
 */
class AndroidEvidenceKeyManager(
    private val aliasPrefix: String = DEFAULT_ALIAS_PREFIX,
    private val logger: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) : EvidenceKeyAccess {

    init {
        require(aliasPrefix.isNotBlank()) { "Keystore alias prefix must not be blank" }
    }

    override fun currentVersion(): Int = CURRENT_VERSION

    /**
     * Lazy generation is allowed only when creating new evidence. If the alias
     * exists but the key is invalidated/unavailable, wrap() fails closed; no new
     * key is generated under that alias.
     */
    @Synchronized
    override fun keyForEncryption(): EvidenceMasterKey = stage("KEYSTORE", "LOAD_OR_GENERATE_KEY") {
        val version = CURRENT_VERSION
        val alias = aliasFor(version)
        if (!loadKeyStore().containsAlias(alias)) {
            generateMasterKey(alias)
        }
        AndroidMasterKey(version, alias)
    }

    /**
     * Reading evidence must fail closed when a key is missing. In particular, a
     * deleted/invalidated v1 alias must not be silently replaced with a new v1
     * key that cannot decrypt evidence written by the original key.
     */
    @Synchronized
    override fun keyForDecryption(version: Int): EvidenceMasterKey =
        stage("KEYSTORE", "LOAD_DECRYPTION_KEY") {
            require(version > 0) { "Key version must be positive: $version" }

            val alias = aliasFor(version)
            if (!loadKeyStore().containsAlias(alias)) {
                throw UnsupportedKeyVersionException(version)
            }
            AndroidMasterKey(version, alias)
        }

    private fun loadKeyStore(): KeyStore {
        val keyStore = cryptoStage("KeyStore.getInstance", "type=$ANDROID_KEYSTORE") {
            KeyStore.getInstance(ANDROID_KEYSTORE)
        }
        cryptoStage("KeyStore.load", "input=none") { keyStore.load(null) }
        return keyStore
    }

    private fun generateMasterKey(alias: String) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Randomized encryption is the default. Do not require biometric
            // authentication or StrongBox; either could make evidence unusable.
            .build()

        // "AndroidKeyStore" DOES register KeyGenerator.AES, so pinning it here is
        // correct. Only the Cipher lookup must use JCA provider discovery.
        val generator = cryptoStage(
            "KeyGenerator.getInstance",
            "algorithm=${KeyProperties.KEY_ALGORITHM_AES},provider=$ANDROID_KEYSTORE"
        ) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        }
        cryptoStage(
            "KeyGenerator.init",
            "keySize=$KEY_SIZE_BITS,blockMode=${KeyProperties.BLOCK_MODE_GCM},padding=None"
        ) { generator.init(spec) }
        cryptoStage("KeyGenerator.generateKey", "purpose=ENCRYPT|DECRYPT") {
            generator.generateKey()
        }
    }

    private fun aliasFor(version: Int): String = "$aliasPrefix.v$version"

    private inner class AndroidMasterKey(
        override val version: Int,
        private val alias: String
    ) : EvidenceMasterKey {

        override fun wrap(dataKey: ByteArray): WrappedKey = stage("KEYSTORE", "WRAP_DATA_KEY") {
            // Look up the cipher via JCA provider discovery. Pinning
            // "AndroidKeyStore" here throws NoSuchAlgorithmException because that
            // provider registers no Cipher algorithms (AOSP AndroidKeyStoreProvider).
            val cipher = cryptoStage(
                "Cipher.getInstance",
                "transformation=${KeystoreCipherFactory.TRANSFORMATION},provider=default"
            ) { KeystoreCipherFactory.create() }
            logger.stage("KEYSTORE", "Cipher.selectedProvider(${cipher.provider.name})")

            // Let Keystore generate the IV. Caller-supplied IVs are rejected for
            // IND-CPA keys by default, which is the desired behavior here.
            cryptoStage("Cipher.init", "mode=ENCRYPT") {
                cipher.init(Cipher.ENCRYPT_MODE, loadSecretKey())
            }
            val encrypted = cryptoStage("Cipher.doFinal", "input=dataKey") {
                cipher.doFinal(dataKey)
            }
            // Read the IV only AFTER doFinal: that is the documented-safe point
            // where the generated IV is available, and the Keystore SPI preserves
            // it across the completed operation. Reading it before doFinal can
            // return null on implementations that expose the IV lazily.
            val iv = cipher.iv
                ?: throw AuthenticationFailedException("Keystore did not return a wrap IV")
            if (iv.size != WRAP_IV_BYTES) {
                throw AuthenticationFailedException("Keystore wrap IV must be $WRAP_IV_BYTES bytes")
            }
            WrappedKey(iv.copyOf(), encrypted)
        }

        override fun unwrap(wrapped: WrappedKey): ByteArray = stage("KEYSTORE", "UNWRAP_DATA_KEY") {
            val cipher = cryptoStage(
                "Cipher.getInstance",
                "transformation=${KeystoreCipherFactory.TRANSFORMATION},provider=default"
            ) { KeystoreCipherFactory.create() }
            logger.stage("KEYSTORE", "Cipher.selectedProvider(${cipher.provider.name})")
            cryptoStage("Cipher.init", "mode=DECRYPT") {
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    loadSecretKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, wrapped.iv)
                )
            }
            cryptoStage("Cipher.doFinal", "input=wrappedEnvelope") {
                cipher.doFinal(wrapped.ciphertextAndTag)
            }
        }

        private fun loadSecretKey(): SecretKey =
            loadKeyStore().getKey(alias, null) as? SecretKey
                ?: throw UnsupportedKeyVersionException(version)
    }

    /** Logs the exact JCA operation, requested algorithm/transformation and provider. */
    private inline fun <T> cryptoStage(api: String, detail: String, block: () -> T): T =
        stage("KEYSTORE", "$api($detail)", block)

    private inline fun <T> stage(operation: String, name: String, block: () -> T): T {
        logger.stage(operation, name)
        return try {
            block()
        } catch (e: Exception) {
            logger.failure(operation, name, e)
            throw e
        }
    }

    companion object {
        const val DEFAULT_ALIAS_PREFIX: String = "com.nightagent.evidence.master"
        const val CURRENT_VERSION: Int = 1
        private const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
        private const val KEY_SIZE_BITS: Int = 256
        private const val TAG_LENGTH_BITS: Int = 128
        private const val WRAP_IV_BYTES: Int = 12
    }
}
