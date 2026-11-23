package com.nightagent.evidence.crypto

import javax.crypto.Cipher

/**
 * Creates the AES-256-GCM cipher used for Keystore-backed data-key wrapping.
 *
 * IMPORTANT — provider selection:
 * The provider literally named "AndroidKeyStore" registers NO `Cipher.*`
 * algorithms. It only registers `KeyStore.AndroidKeyStore`,
 * `KeyGenerator.AES`, `Signature.*`, and similar. The `AES/GCM/NoPadding`
 * cipher for Keystore keys is registered by a *different* provider,
 * `AndroidKeyStoreBCWorkaroundProvider`.
 *
 * Pinning "AndroidKeyStore" here therefore throws `NoSuchAlgorithmException`
 * on real devices:
 *     Cipher.getInstance("AES/GCM/NoPadding", "AndroidKeyStore")  // WRONG
 *
 * Letting the JCA search all installed providers finds the workaround
 * provider, which is Keystore-backed: the key stays non-exportable and the
 * AES-GCM operation is still performed by Android Keystore.
 *     Cipher.getInstance("AES/GCM/NoPadding")                     // CORRECT
 *
 * This is the same pattern used by the official Android Keystore samples.
 */
internal object KeystoreCipherFactory {

    const val TRANSFORMATION: String = "AES/GCM/NoPadding"

    /** Looks up the cipher via JCA provider discovery (no provider pinning). */
    fun create(): Cipher = Cipher.getInstance(TRANSFORMATION)
}
