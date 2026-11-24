package com.nightagent.evidence.integrity

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Outcome of hashing an on-disk encrypted container. */
sealed interface ContainerDigestResult {
    data class Hashed(val hex: String) : ContainerDigestResult
    data object Missing : ContainerDigestResult
    data object Unreadable : ContainerDigestResult
}

/**
 * SHA-256 fingerprints for complete NGE1 container files.
 *
 * This is an additional local consistency check only. It is not proof of
 * provenance or identity, and it does not replace NGE1's AES-GCM authentication.
 */
object ContainerDigests {

    const val BUFFER_SIZE_BYTES: Int = 64 * 1024

    fun sha256Hex(bytes: ByteArray): String = sha256Hex(java.io.ByteArrayInputStream(bytes))

    /** Reads [input] incrementally; the caller owns and closes the stream. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return toLowerHex(digest.digest())
    }

    /** Hashes a published container without modifying it or loading it all into memory. */
    fun ofFile(file: File): ContainerDigestResult {
        if (!file.exists()) return ContainerDigestResult.Missing
        if (!file.isFile) return ContainerDigestResult.Unreadable
        return try {
            FileInputStream(file).use { input ->
                ContainerDigestResult.Hashed(sha256Hex(input))
            }
        } catch (e: IOException) {
            ContainerDigestResult.Unreadable
        } catch (e: SecurityException) {
            ContainerDigestResult.Unreadable
        }
    }

    private fun toLowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xFF
            chars[index * 2] = digits[unsigned ushr 4]
            chars[index * 2 + 1] = digits[unsigned and 0x0F]
        }
        return String(chars)
    }
}
