package com.nightagent.evidence.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Unit tests for bounded SHA-256 fingerprinting of encrypted containers. */
class ContainerDigestsTest {

    @Test
    fun knownSha256VectorsMatch() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContainerDigests.sha256Hex("abc".toByteArray(StandardCharsets.UTF_8))
        )
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            ContainerDigests.sha256Hex("hello world".toByteArray(StandardCharsets.UTF_8))
        )
    }

    @Test
    fun emptyInputHasStableLowercaseDigest() {
        val digest = ContainerDigests.sha256Hex(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest
        )
        assertEquals(digest.lowercase(), digest)
        assertTrue(digest.none { it.isUpperCase() })
    }

    @Test
    fun streamHashingMatchesByteArrayHashing() {
        val bytes = ByteArray(ContainerDigests.BUFFER_SIZE_BYTES * 2 + 17) { (it % 251).toByte() }
        assertEquals(
            ContainerDigests.sha256Hex(bytes),
            ContainerDigests.sha256Hex(ByteArrayInputStream(bytes))
        )
    }

    @Test
    fun fileHashingMatchesExpectedDigestAndDoesNotModifyFile() {
        val file = Files.createTempFile("container_digest", ".ng1").toFile()
        val bytes = ByteArray(ContainerDigests.BUFFER_SIZE_BYTES + 31) { (it % 97).toByte() }
        file.writeBytes(bytes)

        val result = ContainerDigests.ofFile(file)

        assertTrue(result is ContainerDigestResult.Hashed)
        assertEquals(ContainerDigests.sha256Hex(bytes), (result as ContainerDigestResult.Hashed).hex)
        assertTrue(bytes.contentEquals(Files.readAllBytes(file.toPath())))
    }

    @Test
    fun emptyFileUsesEmptyInputDigest() {
        val file = Files.createTempFile("empty_container_digest", ".ng1").toFile()
        val result = ContainerDigests.ofFile(file)

        assertTrue(result is ContainerDigestResult.Hashed)
        assertEquals(ContainerDigests.sha256Hex(ByteArray(0)), (result as ContainerDigestResult.Hashed).hex)
    }

    @Test
    fun missingFileIsReportedWithoutThrowing() {
        val missing = File(Files.createTempDirectory("missing_digest").toFile(), "absent.ng1")
        assertEquals(ContainerDigestResult.Missing, ContainerDigests.ofFile(missing))
    }

    @Test
    fun unreadableFileIsReportedWithoutThrowing() {
        val directory = Files.createTempDirectory("digest_directory_as_file").toFile()
        assertEquals(ContainerDigestResult.Unreadable, ContainerDigests.ofFile(directory))
    }
}
