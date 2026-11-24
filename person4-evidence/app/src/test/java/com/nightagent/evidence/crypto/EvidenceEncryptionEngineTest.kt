package com.nightagent.evidence.crypto

import com.nightagent.evidence.model.EvidenceType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JVM tests for the standalone NGE1 engine using injected keys. */
class EvidenceEncryptionEngineTest {

    private data class Fixture(
        val plaintext: File,
        val plaintextBytes: ByteArray,
        val encrypted: File,
        val result: EncryptionResult,
        val header: Nge1Container.Header,
        val engine: EvidenceEncryptionEngine
    )

    @Test
    fun smallFileRoundTripLeavesPlaintextUntouched() {
        val fixture = fixture(size = 5, chunkSize = 16)
        val output = File(fixture.plaintext.parentFile, "decoded.mp4")

        fixture.engine.verify(fixture.encrypted)
        fixture.engine.decrypt(fixture.encrypted, output)

        assertArrayEquals(fixture.plaintextBytes, fixture.plaintext.readBytes())
        assertArrayEquals(fixture.plaintextBytes, output.readBytes())
        assertEquals("mp4", fixture.header.metadata.originalExtension)
        assertEquals(EvidenceType.VIDEO, fixture.header.metadata.mediaType)
    }

    @Test
    fun multiChunkRoundTripUsesIndependentChunks() {
        val fixture = fixture(size = 25, chunkSize = 7)
        val output = File(fixture.plaintext.parentFile, "decoded.mp4")

        assertEquals(4L, fixture.result.chunkCount)
        fixture.engine.decrypt(fixture.encrypted, output)
        assertArrayEquals(fixture.plaintextBytes, output.readBytes())
    }

    @Test
    fun emptyAndInvalidInputsAreRejected() {
        val empty = writePlaintext(byteArrayOf(), "empty.mp4")
        val target = File(empty.parentFile, "empty.ng1")

        assertThrows(IllegalArgumentException::class.java) {
            engine().encrypt(empty, target, EvidenceType.VIDEO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceEncryptionEngine(keys(), JvmAtomicFilePublisher(), 0)
        }
    }

    @Test
    fun existingTargetIsNeverOverwritten() {
        val plaintextBytes = ByteArray(12) { it.toByte() }
        val plaintext = writePlaintext(plaintextBytes)
        val target = writePlaintext("existing".toByteArray(), "encrypted.ng1")

        assertThrows(IOException::class.java) {
            engine().encrypt(plaintext, target, EvidenceType.VIDEO)
        }
        assertEquals("existing", target.readText())
        assertArrayEquals(plaintextBytes, plaintext.readBytes())
        assertTrue(temporaryFiles(plaintext.parentFile).isEmpty())
    }

    @Test
    fun publicationFailurePreservesPlaintextAndIsLogged() {
        val plaintextBytes = ByteArray(12) { it.toByte() }
        val plaintext = writePlaintext(plaintextBytes)
        val target = File(plaintext.parentFile, "publish_failure.ng1")
        val failingPublisher = object : AtomicFilePublisher {
            override fun publish(temporary: File, target: File) {
                throw IOException("simulated publication failure")
            }
        }
        val logger = RecordingDiagnosticLogger()
        val engine = EvidenceEncryptionEngine(keys(), failingPublisher, 7, logger)

        assertThrows(IOException::class.java) {
            engine.encrypt(plaintext, target, EvidenceType.VIDEO)
        }

        assertFalse(target.exists())
        assertArrayEquals(plaintextBytes, plaintext.readBytes())
        assertTrue(temporaryFiles(plaintext.parentFile).isEmpty())
        assertTrue(logger.stages.any { it.second == "PUBLISH" })
        assertTrue(
            logger.failures.any {
                it.second == "PUBLISH" && it.third == IOException::class.java.simpleName
            }
        )
    }

    @Test
    fun successfulRoundTripCleansTemporaryFiles() {
        val fixture = fixture()
        val output = File(fixture.plaintext.parentFile, "decoded.mp4")

        fixture.engine.decrypt(fixture.encrypted, output)

        assertTrue(output.isFile)
        assertTrue(temporaryFiles(fixture.plaintext.parentFile).isEmpty())
    }

    @Test
    fun failedDecryptionCleansTemporaryFiles() {
        val fixture = fixture(size = 25, chunkSize = 7)
        val tampered = copyTo(fixture.encrypted, "bad_cipher.ng1")
        flipByte(tampered, fixture.header.bytes.size.toLong() + 1L)
        val output = File(fixture.plaintext.parentFile, "failed_decoded.mp4")

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.decrypt(tampered, output)
        }
        assertTrue(!output.exists())
        assertTrue(temporaryFiles(fixture.plaintext.parentFile).isEmpty())
    }

    @Test
    fun twoEncryptionsUseFreshFileIdsNoncesAndDataKeys() {
        val first = fixture(size = 25, chunkSize = 7)
        val second = fixture(size = 25, chunkSize = 7)

        assertNotEquals(first.header.metadata.fileId, second.header.metadata.fileId)
        assertFalse(
            first.header.metadata.fileNonce.contentEquals(second.header.metadata.fileNonce)
        )
        assertFalse(
            first.header.metadata.wrappedKey == second.header.metadata.wrappedKey
        )
        assertArrayEquals(first.plaintextBytes, second.plaintextBytes)
    }

    @Test
    fun corruptedWrappedKeyIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "bad_wrapped_key.ng1")
        val wrappedLength = fixture.header.metadata.wrappedKey.ciphertextAndTag.size
        val wrappedOffset = fixture.header.bytes.size.toLong() - wrappedLength + 1L

        flipByte(tampered, wrappedOffset)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun lastChunkTagTamperingIsRejected() {
        val fixture = fixture(size = 25, chunkSize = 7)
        val tampered = copyTo(fixture.encrypted, "bad_last_tag.ng1")
        val metadata = fixture.header.metadata
        val lastTagOffset = fixture.header.bytes.size.toLong() +
            metadata.plaintextLength +
            (metadata.chunkCount - 1) * Nge1Container.TAG_LENGTH_BYTES

        flipByte(tampered, lastTagOffset)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun missingDecryptionKeyDoesNotGenerateReplacement() {
        val key = TestMasterKey.seeded(0x71)
        val encryptAccess = TestKeyAccess(
            keys = emptyList(),
            currentVersion = 1,
            encryptionKey = key
        )
        val plaintext = writePlaintext(ByteArray(20) { it.toByte() })
        val encrypted = File(plaintext.parentFile, "lazy_key.ng1")

        engine(keyAccess = encryptAccess).encrypt(plaintext, encrypted, EvidenceType.VIDEO)

        val decryptionAccess = TestKeyAccess(keys = emptyList(), currentVersion = 1)
        assertThrows(UnsupportedKeyVersionException::class.java) {
            engine(keyAccess = decryptionAccess).verify(encrypted)
        }
    }

    @Test
    fun invalidatedDecryptionKeyFailsClosed() {
        val fixture = fixture()
        val invalidAccess = TestKeyAccess(
            keys = listOf(InvalidatedTestMasterKey(fixture.result.keyVersion)),
            currentVersion = fixture.result.keyVersion
        )

        assertThrows(AuthenticationFailedException::class.java) {
            engine(keyAccess = invalidAccess).verify(fixture.encrypted)
        }
    }

    @Test
    fun headerTamperingIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "header_tampered.ng1")

        // Offset 16 is inside the file UUID; the header still parses, but footer
        // authentication covers the exact header bytes.
        flipByte(tampered, 16L)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun ciphertextTamperingIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "cipher_tampered.ng1")
        flipByte(tampered, fixture.header.bytes.size.toLong() + 2L)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun chunkTagTamperingIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "tag_tampered.ng1")
        val tagOffset = fixture.header.bytes.size.toLong() + fixture.header.metadata.plaintextLength

        flipByte(tampered, tagOffset)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun footerTamperingIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "footer_tampered.ng1")
        val footerOffset = fixture.header.bytes.size.toLong() +
            fixture.header.metadata.plaintextLength +
            fixture.header.metadata.chunkCount * Nge1Container.TAG_LENGTH_BYTES

        flipByte(tampered, footerOffset)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun truncatedFileIsRejected() {
        val fixture = fixture()
        val truncated = File(fixture.plaintext.parentFile, "truncated.ng1")
        copyPrefix(fixture.encrypted, truncated, fixture.encrypted.length() - 1)

        assertThrows(ContainerFormatException::class.java) {
            fixture.engine.verify(truncated)
        }
    }

    @Test
    fun appendedBytesAreRejected() {
        val fixture = fixture()
        val appended = copyTo(fixture.encrypted, "appended.ng1")
        FileOutputStream(appended, true).use { it.write(0x41) }

        assertThrows(ContainerFormatException::class.java) {
            fixture.engine.verify(appended)
        }
    }

    @Test
    fun reorderedChunksAreRejected() {
        val fixture = fixture(size = 25, chunkSize = 7)
        val reordered = copyTo(fixture.encrypted, "reordered.ng1")
        swapChunks(reordered, fixture.header.bytes.size, fixture.header.metadata, 0L, 1L)

        assertThrows(AuthenticationFailedException::class.java) {
            fixture.engine.verify(reordered)
        }
    }

    @Test
    fun unsupportedFormatVersionIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "bad_version.ng1")
        writeIntAt(tampered, 8L, 99)

        assertThrows(ContainerFormatException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun unsupportedKeyVersionIsRejected() {
        val fixture = fixture()
        val tampered = copyTo(fixture.encrypted, "bad_key_version.ng1")
        writeIntAt(tampered, 12L, 2)

        assertThrows(UnsupportedKeyVersionException::class.java) {
            fixture.engine.verify(tampered)
        }
    }

    @Test
    fun incorrectKeyIsRejected() {
        val fixture = fixture()
        val wrongKeyEngine = engine(keyAccess = keys(TestMasterKey.seeded(0x99)))
        val output = File(fixture.plaintext.parentFile, "wrong_key_output.mp4")

        assertThrows(AuthenticationFailedException::class.java) {
            wrongKeyEngine.decrypt(fixture.encrypted, output)
        }
        assertTrue(!output.exists())
    }

    @Test
    fun largeSyntheticFileUsesBoundedChunkProcessing() {
        val size = 4 * 1024 * 1024
        val bytes = ByteArray(size) { (it * 31 + 5).toByte() }
        val plaintext = writePlaintext(bytes, "large.mp4")
        val encrypted = File(plaintext.parentFile, "large.ng1")
        val output = File(plaintext.parentFile, "large_decoded.mp4")
        val boundedEngine = engine(chunkSize = 64 * 1024)

        val result = boundedEngine.encrypt(plaintext, encrypted, EvidenceType.VIDEO)

        assertEquals(64L, result.chunkCount)
        boundedEngine.decrypt(encrypted, output)
        assertEquals(size.toLong(), output.length())
        assertArrayEquals(bytes, output.readBytes())
    }

    private fun fixture(
        size: Int = 25,
        chunkSize: Int = 7,
        key: EvidenceMasterKey = primaryKey
    ): Fixture {
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        val plaintext = writePlaintext(bytes)
        val encrypted = File(plaintext.parentFile, "encrypted.ng1")
        val testEngine = engine(chunkSize, keys(key))
        val result = testEngine.encrypt(plaintext, encrypted, EvidenceType.VIDEO)
        return Fixture(
            plaintext = plaintext,
            plaintextBytes = bytes,
            encrypted = encrypted,
            result = result,
            header = Nge1Container.readHeader(encrypted),
            engine = testEngine
        )
    }

    private fun writePlaintext(content: ByteArray, name: String = "clip.mp4"): File {
        val directory = Files.createTempDirectory("nge1_engine_test").toFile()
        return File(directory, name).apply { writeBytes(content) }
    }

    private fun temporaryFiles(directory: File?): List<File> =
        directory?.let { File(it, ".nge1-tmp") }
            ?.listFiles()
            ?.toList()
            .orEmpty()

    private fun keys(key: EvidenceMasterKey = primaryKey): TestKeyAccess =
        TestKeyAccess(listOf(key), currentVersion = key.version)

    private fun engine(
        chunkSize: Int = 7,
        keyAccess: EvidenceKeyAccess = keys()
    ): EvidenceEncryptionEngine =
        EvidenceEncryptionEngine(keyAccess, JvmAtomicFilePublisher(), chunkSize)

    private fun copyTo(source: File, name: String): File {
        val target = File(source.parentFile, name)
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private fun copyPrefix(source: File, target: File, length: Long) {
        RandomAccessFile(source, "r").use { input ->
            FileOutputStream(target).use { output ->
                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) throw IOException("Unexpected EOF in $source")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun flipByte(file: File, offset: Long) {
        RandomAccessFile(file, "rw").use { access ->
            access.seek(offset)
            val current = access.read()
            if (current < 0) throw IOException("Unexpected EOF in $file")
            access.write(current xor 1)
        }
    }

    private fun writeIntAt(file: File, offset: Long, value: Int) {
        RandomAccessFile(file, "rw").use { access ->
            access.seek(offset)
            access.writeInt(value)
        }
    }

    private fun swapChunks(
        file: File,
        headerSize: Int,
        metadata: Nge1Container.Metadata,
        first: Long,
        second: Long
    ) {
        val firstLength = Nge1Container.plaintextChunkLength(metadata, first)
        val secondLength = Nge1Container.plaintextChunkLength(metadata, second)
        require(firstLength == secondLength) { "Test requires equal-length chunks" }

        val firstOffset = headerSize.toLong() + first * metadata.chunkSize
        val secondOffset = headerSize.toLong() + second * metadata.chunkSize
        val firstBytes = ByteArray(firstLength)
        val secondBytes = ByteArray(secondLength)

        RandomAccessFile(file, "rw").use { access ->
            access.seek(firstOffset)
            access.readFully(firstBytes)
            access.seek(secondOffset)
            access.readFully(secondBytes)
            access.seek(firstOffset)
            access.write(secondBytes)
            access.seek(secondOffset)
            access.write(firstBytes)
        }
    }

    companion object {
        private val primaryKey: EvidenceMasterKey = TestMasterKey.seeded(0x21)
    }
}

private class RecordingDiagnosticLogger : EvidenceDiagnosticLogger {
    val stages = mutableListOf<Pair<String, String>>()
    val failures = mutableListOf<Triple<String, String, String>>()

    override fun stage(operation: String, stage: String) {
        stages += operation to stage
    }

    override fun failure(operation: String, stage: String, error: Throwable) {
        failures += Triple(operation, stage, error::class.java.simpleName)
    }
}
