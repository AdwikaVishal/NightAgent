package com.nightagent.evidence.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Regression tests for the real-device failure:
 *
 *   WRAP_DATA_KEY / NoSuchAlgorithmException
 *
 * Root cause: the provider named "AndroidKeyStore" registers no Cipher
 * algorithms. Requesting AES/GCM/NoPadding from it throws
 * NoSuchAlgorithmException. The cipher must be found via JCA provider
 * discovery, which locates the Keystore-backed BC workaround provider.
 */
class KeystoreCipherFactoryTest {

    @Test
    fun pinningAndroidKeyStoreProviderCannotFindGcmCipher() {
        // Reproduces the exact device failure mode. On a device the named
        // provider exists but registers no Cipher algorithms, so this throws
        // NoSuchAlgorithmException (our device diagnostic). On the host JVM the
        // provider is absent, so this throws NoSuchProviderException. Both are
        // GeneralSecurityException and both prove the lookup is invalid.
        val error = assertThrows(GeneralSecurityException::class.java) {
            Cipher.getInstance("AES/GCM/NoPadding", "AndroidKeyStore")
        }
        assertNotNull(error)
    }

    @Test
    fun defaultProviderLookupReturnsUsableGcmCipher() {
        val cipher = KeystoreCipherFactory.create()
        assertNotNull(cipher)

        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plaintext = "encrypted evidence".toByteArray()

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val envelope = cipher.doFinal(plaintext)

        val decrypt = KeystoreCipherFactory.create()
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        assertArrayEquals(plaintext, decrypt.doFinal(envelope))
    }

    @Test
    fun transformationIsAesGcmNoPadding() {
        assertEquals("AES/GCM/NoPadding", KeystoreCipherFactory.TRANSFORMATION)
    }
}
