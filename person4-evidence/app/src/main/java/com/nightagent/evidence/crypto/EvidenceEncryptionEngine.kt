package com.nightagent.evidence.crypto

import com.nightagent.evidence.model.EvidenceType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Successful encryption result. The plaintext original is intentionally untouched. */
data class EncryptionResult(
    val file: File,
    val fileId: UUID,
    val mediaType: EvidenceType,
    val plaintextLength: Long,
    val chunkCount: Long,
    val keyVersion: Int
)

/** Fully verified container metadata. */
data class VerifiedContainer(
    val file: File,
    val header: Nge1Container.Header
) {
    val metadata: Nge1Container.Metadata get() = header.metadata
}

/**
 * Standalone NGE1 encryption engine.
 *
 * This class contains no UI code and does not delete plaintext originals. Every
 * chunk uses a fresh Cipher with its own IV, AAD and authentication tag. The tag
 * table is streamed through bounded buffers rather than held in memory.
 */
class EvidenceEncryptionEngine(
    private val keyAccess: EvidenceKeyAccess,
    private val publisher: AtomicFilePublisher,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val logger: EvidenceDiagnosticLogger = SilentEvidenceDiagnosticLogger
) {

    init {
        Nge1Container.validateChunkSize(chunkSize)
    }

    fun encrypt(plaintext: File, target: File, mediaType: EvidenceType): EncryptionResult {
        if (!plaintext.isFile || plaintext.length() <= 0L) {
            throw IllegalArgumentException("Plaintext evidence must be a non-empty file")
        }

        val tempDirectory = prepareTempDirectory(target)
        val partFile = File(tempDirectory, "${UUID.randomUUID()}.enc.part")
        val tagFile = File(tempDirectory, "${UUID.randomUUID()}.tags")
        val dataKey = ByteArray(DATA_KEY_BYTES).also { SECURE_RANDOM.nextBytes(it) }

        val operation = mediaType.name
        try {
            val masterKey = stage(operation, "MASTER_KEY") { keyAccess.keyForEncryption() }
            val keyVersion = masterKey.version
            val wrappedKey = stage(operation, "WRAP_DATA_KEY") {
                unwrapErrors("Master key could not wrap data key") {
                    masterKey.wrap(dataKey)
                }
            }

            val metadata = stage(operation, "BUILD_METADATA") {
                Nge1Container.Metadata(
                    keyVersion = keyVersion,
                    fileId = UUID.randomUUID(),
                    fileNonce = Nge1Container.newFileNonce(),
                    mediaType = mediaType,
                    originalExtension = extensionFor(plaintext, mediaType),
                    plaintextLength = plaintext.length(),
                    chunkSize = chunkSize,
                    chunkCount = Nge1Container.expectedChunkCount(plaintext.length(), chunkSize),
                    wrappedKey = wrappedKey
                )
            }
            val header = stage(operation, "ENCODE_HEADER") {
                Nge1Container.encodeHeader(metadata)
            }

            stage(operation, "WRITE_PAYLOAD") {
                writePayload(plaintext, partFile, tagFile, header, metadata, dataKey)
            }
            stage(operation, "WRITE_FOOTER") {
                appendFooter(partFile, tagFile, header, metadata, dataKey)
            }

            // Verify with the exact master key used for this encryption. This
            // is not a read of pre-existing evidence, so it must not depend on
            // the decryption-only key lookup path.
            stage(operation, "VERIFY_BEFORE_PUBLISH") {
                verifyWithMasterKey(
                    partFile,
                    Nge1Container.Header(metadata, header),
                    masterKey
                )
            }

            stage(operation, "PUBLISH") { publisher.publish(partFile, target) }
            logger.stage(operation, "ENCRYPT_SUCCESS")
            return EncryptionResult(
                file = target,
                fileId = metadata.fileId,
                mediaType = metadata.mediaType,
                plaintextLength = metadata.plaintextLength,
                chunkCount = metadata.chunkCount,
                keyVersion = keyVersion
            )
        } catch (e: Exception) {
            logger.failure(operation, "ENCRYPT", e)
            throw e
        } finally {
            dataKey.fill(0)
            deleteQuietly(partFile)
            deleteQuietly(tagFile)
        }
    }

    /** Verifies header, structure, footer and every authenticated chunk. */
    fun verify(encrypted: File): VerifiedContainer {
        val operation = "VERIFY_CONTAINER"
        try {
            val header = stage(operation, "READ_HEADER") {
                Nge1Container.readHeader(encrypted)
            }
            val masterKey = stage(operation, "DECRYPTION_KEY") {
                keyAccess.keyForDecryption(header.metadata.keyVersion)
            }
            val verified = stage(operation, "VERIFY_CHUNKS_AND_FOOTER") {
                verifyWithMasterKey(encrypted, header, masterKey)
            }
            logger.stage(operation, "VERIFY_SUCCESS")
            return verified
        } catch (e: Exception) {
            logger.failure(operation, "VERIFY", e)
            throw e
        }
    }

    private fun verifyWithMasterKey(
        encrypted: File,
        header: Nge1Container.Header,
        masterKey: EvidenceMasterKey
    ): VerifiedContainer {
        val metadata = header.metadata
        val tagTableOffset = Nge1Container.tagTableOffset(header.bytes.size, metadata)
        val expectedLength = Nge1Container.containerLength(header.bytes.size, metadata)
        if (encrypted.length() != expectedLength) {
            throw ContainerFormatException(
                "Invalid NGE1 length: expected $expectedLength bytes, found ${encrypted.length()}"
            )
        }

        val dataKey = unwrapKey(metadata, masterKey)
        return try {
            RandomAccessFile(encrypted, "r").use { input ->
                verifyFooter(input, header, metadata, tagTableOffset, dataKey)
                verifyChunks(encrypted, input, header, dataKey, null)
            }
            VerifiedContainer(encrypted, header)
        } finally {
            dataKey.fill(0)
        }
    }

    /**
     * Verifies the container, then writes plaintext only after each chunk has
     * passed authenticated decryption. The output is published only after all
     * chunks verify; failure removes the incomplete decrypted temporary file.
     */
    fun decrypt(encrypted: File, target: File): VerifiedContainer {
        val verified = verify(encrypted)
        val tempDirectory = prepareTempDirectory(target)
        val temporary = File(tempDirectory, "${UUID.randomUUID()}.dec.part")
        val masterKey = keyAccess.keyForDecryption(verified.metadata.keyVersion)
        val dataKey = unwrapKey(verified.metadata, masterKey)

        try {
            FileOutputStream(temporary).use { output ->
                RandomAccessFile(encrypted, "r").use { input ->
                    verifyChunks(encrypted, input, verified.header, dataKey, output)
                }
                output.flush()
                output.fd.sync()
            }
            publisher.publish(temporary, target)
            return verified
        } finally {
            dataKey.fill(0)
            deleteQuietly(temporary)
        }
    }

    private fun writePayload(
        plaintext: File,
        partFile: File,
        tagFile: File,
        header: ByteArray,
        metadata: Nge1Container.Metadata,
        dataKey: ByteArray
    ) {
        FileOutputStream(partFile).use { container ->
            FileOutputStream(tagFile).use { tags ->
                container.write(header)

                RandomAccessFile(plaintext, "r").use { input ->
                    for (index in 0 until metadata.chunkCount) {
                        val plainLength = Nge1Container.plaintextChunkLength(metadata, index)
                        val plainChunk = ByteArray(plainLength)
                        input.readFully(plainChunk)

                        val cipher = newChunkCipher(
                            dataKey,
                            Nge1Container.chunkIv(metadata.fileNonce, index),
                            Cipher.ENCRYPT_MODE
                        )
                        cipher.updateAAD(
                            Nge1Container.chunkAad(header, index, plainLength)
                        )
                        val sealed = cipher.doFinal(plainChunk)
                        if (sealed.size != plainLength + Nge1Container.TAG_LENGTH_BYTES) {
                            throw IOException("Unexpected encrypted chunk length")
                        }

                        container.write(sealed, 0, plainLength)
                        tags.write(sealed, plainLength, Nge1Container.TAG_LENGTH_BYTES)
                    }
                }

                container.flush()
                container.fd.sync()
                tags.flush()
                tags.fd.sync()
            }
        }
    }

    private fun appendFooter(
        partFile: File,
        tagFile: File,
        header: ByteArray,
        metadata: Nge1Container.Metadata,
        dataKey: ByteArray
    ) {
        FileOutputStream(partFile, true).use { output ->
            FileInputStream(tagFile).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var read = input.read(buffer)
                while (read > 0) {
                    output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }

            val footerTag = computeFooterTag(header, tagFile, metadata, dataKey)
            output.write(footerTag)
            output.flush()
            output.fd.sync()
        }
    }

    private fun computeFooterTag(
        header: ByteArray,
        tagFile: File,
        metadata: Nge1Container.Metadata,
        dataKey: ByteArray
    ): ByteArray {
        val cipher = newChunkCipher(
            dataKey,
            Nge1Container.footerIv(metadata.fileNonce),
            Cipher.ENCRYPT_MODE
        )
        cipher.updateAAD(header)

        FileInputStream(tagFile).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var read = input.read(buffer)
            while (read > 0) {
                cipher.updateAAD(buffer, 0, read)
                read = input.read(buffer)
            }
        }

        val result = cipher.doFinal(ByteArray(0))
        if (result.size != Nge1Container.TAG_LENGTH_BYTES) {
            throw IOException("Unexpected footer tag length")
        }
        return result
    }

    private fun verifyFooter(
        input: RandomAccessFile,
        header: Nge1Container.Header,
        metadata: Nge1Container.Metadata,
        tagTableOffset: Long,
        dataKey: ByteArray
    ) {
        val cipher = newChunkCipher(
            dataKey,
            Nge1Container.footerIv(metadata.fileNonce),
            Cipher.DECRYPT_MODE
        )
        cipher.updateAAD(header.bytes)

        input.seek(tagTableOffset)
        var remaining = Nge1Container.footerSize(metadata.chunkCount) -
            Nge1Container.TAG_LENGTH_BYTES
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw ContainerFormatException("Truncated chunk tag table")
            cipher.updateAAD(buffer, 0, read)
            remaining -= read
        }

        val footerTag = ByteArray(Nge1Container.TAG_LENGTH_BYTES)
        input.readFully(footerTag)
        authenticated("Footer authentication failed") {
            cipher.doFinal(footerTag)
        }
    }

    private fun verifyChunks(
        container: File,
        ciphertextInput: RandomAccessFile,
        header: Nge1Container.Header,
        dataKey: ByteArray,
        plaintextOutput: OutputStream?
    ) {
        val metadata = header.metadata
        val tagTableOffset = Nge1Container.tagTableOffset(header.bytes.size, metadata)
        ciphertextInput.seek(header.bytes.size.toLong())

        RandomAccessFile(container, "r").use { tagInput ->
            for (index in 0 until metadata.chunkCount) {
                val plainLength = Nge1Container.plaintextChunkLength(metadata, index)
                val ciphertext = ByteArray(plainLength)
                ciphertextInput.readFully(ciphertext)

                tagInput.seek(tagTableOffset + index * Nge1Container.TAG_LENGTH_BYTES)
                val tag = ByteArray(Nge1Container.TAG_LENGTH_BYTES)
                tagInput.readFully(tag)

                val sealed = ByteArray(plainLength + Nge1Container.TAG_LENGTH_BYTES)
                System.arraycopy(ciphertext, 0, sealed, 0, plainLength)
                System.arraycopy(
                    tag, 0, sealed, plainLength, Nge1Container.TAG_LENGTH_BYTES
                )

                val cipher = newChunkCipher(
                    dataKey,
                    Nge1Container.chunkIv(metadata.fileNonce, index),
                    Cipher.DECRYPT_MODE
                )
                cipher.updateAAD(Nge1Container.chunkAad(header.bytes, index, plainLength))
                val plaintext = authenticated("Chunk $index authentication failed") {
                    cipher.doFinal(sealed)
                }
                if (plaintext.size != plainLength) {
                    throw ContainerFormatException("Chunk $index has an invalid plaintext length")
                }
                plaintextOutput?.write(plaintext)
            }
        }
    }

    private fun unwrapKey(
        metadata: Nge1Container.Metadata,
        masterKey: EvidenceMasterKey
    ): ByteArray {
        val unwrapped = unwrapErrors("Master key cannot unwrap this container's data key") {
            masterKey.unwrap(metadata.wrappedKey)
        }
        if (unwrapped.size != DATA_KEY_BYTES) {
            unwrapped.fill(0)
            throw AuthenticationFailedException("Unwrapped data key has an invalid length")
        }
        return unwrapped
    }

    private fun newChunkCipher(dataKey: ByteArray, iv: ByteArray, mode: Int): Cipher {
        val key: SecretKey = SecretKeySpec(dataKey, "AES")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, key, GCMParameterSpec(Nge1Container.TAG_LENGTH_BITS, iv))
        }
    }

    private fun extensionFor(plaintext: File, mediaType: EvidenceType): String {
        val extension = plaintext.extension.ifEmpty {
            when (mediaType) {
                EvidenceType.PHOTO -> "jpg"
                EvidenceType.AUDIO -> "m4a"
                EvidenceType.VIDEO -> "mp4"
            }
        }
        require(extension.length in 1..Nge1Container.MAX_EXTENSION_BYTES) {
            "Extension is too long"
        }
        require(extension.all { it.isLetterOrDigit() }) {
            "Extension must be alphanumeric"
        }
        return extension
    }

    private fun prepareTempDirectory(target: File): File {
        val parent = target.parentFile
            ?: throw IllegalArgumentException("Encrypted output must have a parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create output directory: ${parent.path}")
        }
        val temporary = File(parent, TEMP_DIRECTORY_NAME)
        if (!temporary.exists() && !temporary.mkdirs()) {
            throw IOException("Could not create temporary directory: ${temporary.path}")
        }
        return temporary
    }

    private fun deleteQuietly(file: File) {
        runCatching {
            if (file.exists() && !file.delete()) file.deleteOnExit()
        }
    }

    private inline fun <T> stage(operation: String, name: String, block: () -> T): T {
        logger.stage(operation, name)
        return try {
            block()
        } catch (e: Exception) {
            logger.failure(operation, name, e)
            throw e
        }
    }

    private inline fun <T> unwrapErrors(message: String, block: () -> T): T =
        try {
            block()
        } catch (e: AEADBadTagException) {
            throw AuthenticationFailedException(message, e)
        } catch (e: GeneralSecurityException) {
            throw AuthenticationFailedException(message, e)
        }

    private inline fun <T> authenticated(message: String, block: () -> T): T =
        try {
            block()
        } catch (e: AEADBadTagException) {
            throw AuthenticationFailedException(message, e)
        }

    companion object {
        const val DEFAULT_CHUNK_SIZE: Int = 1024 * 1024
        private const val DATA_KEY_BYTES: Int = 32
        private const val COPY_BUFFER_BYTES: Int = 64 * 1024
        private const val TEMP_DIRECTORY_NAME: String = ".nge1-tmp"
        private const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        private val SECURE_RANDOM = SecureRandom()
    }
}
