package com.nightagent.evidence.crypto

import com.nightagent.evidence.model.EvidenceType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

/**
 * Pure format helpers for the versioned NGE1 encrypted evidence container.
 *
 * Layout:
 *   header | ciphertext chunks | chunk tag table | footer tag
 *
 * The header is small and self-describing. The tag table is deliberately not
 * represented as an in-memory list; callers stream it through bounded buffers.
 */
object Nge1Container {

    const val MAGIC: String = "NGE1FILE"
    const val FORMAT_VERSION: Int = 1
    const val TAG_LENGTH_BYTES: Int = 16
    const val TAG_LENGTH_BITS: Int = 128
    const val FILE_ID_BYTES: Int = 16
    const val FILE_NONCE_BYTES: Int = 12
    const val MAX_HEADER_BYTES: Int = 4096
    const val MAX_EXTENSION_BYTES: Int = 16
    const val MAX_CHUNK_SIZE: Int = 8 * 1024 * 1024
    const val RESERVED_CHUNK_INDEX: Long = 0xFFFF_FFFFL
    const val MAX_CHUNK_INDEX: Long = RESERVED_CHUNK_INDEX - 1L

    private val MAGIC_BYTES: ByteArray = MAGIC.toByteArray(StandardCharsets.US_ASCII)
    private val SECURE_RANDOM = SecureRandom()

    data class Metadata(
        val keyVersion: Int,
        val fileId: UUID,
        val fileNonce: ByteArray,
        val mediaType: EvidenceType,
        val originalExtension: String,
        val plaintextLength: Long,
        val chunkSize: Int,
        val chunkCount: Long,
        val wrappedKey: WrappedKey
    )

    /** Exact header bytes are retained so they can be used verbatim as AAD. */
    data class Header(
        val metadata: Metadata,
        val bytes: ByteArray
    )

    fun validateChunkSize(chunkSize: Int) {
        require(chunkSize in 1..MAX_CHUNK_SIZE) {
            "Chunk size must be between 1 and $MAX_CHUNK_SIZE bytes: $chunkSize"
        }
    }

    fun expectedChunkCount(plaintextLength: Long, chunkSize: Int): Long {
        require(plaintextLength > 0) { "Plaintext length must be positive" }
        validateChunkSize(chunkSize)
        val count = plaintextLength / chunkSize +
            if (plaintextLength % chunkSize == 0L) 0L else 1L
        require(count <= MAX_CHUNK_INDEX) { "Too many chunks for NGE1: $count" }
        return count
    }

    fun footerSize(chunkCount: Long): Long = try {
        Math.addExact(Math.multiplyExact(chunkCount, TAG_LENGTH_BYTES.toLong()), TAG_LENGTH_BYTES.toLong())
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("Chunk tag table is too large", e)
    }

    /** Exact offset of the chunk tag table; rejects malformed arithmetic. */
    fun tagTableOffset(headerSize: Int, metadata: Metadata): Long = try {
        Math.addExact(headerSize.toLong(), metadata.plaintextLength)
    } catch (e: ArithmeticException) {
        throw ContainerFormatException("NGE1 tag table offset overflows", e)
    }

    /** Exact total container length; rejects malformed arithmetic. */
    fun containerLength(headerSize: Int, metadata: Metadata): Long = try {
        Math.addExact(tagTableOffset(headerSize, metadata), footerSize(metadata.chunkCount))
    } catch (e: ArithmeticException) {
        throw ContainerFormatException("NGE1 container length overflows", e)
    }

    fun plaintextChunkLength(metadata: Metadata, index: Long): Int {
        require(index in 0 until metadata.chunkCount) { "Invalid chunk index: $index" }
        val remaining = metadata.plaintextLength - index * metadata.chunkSize
        return minOf(metadata.chunkSize.toLong(), remaining).toInt()
    }

    fun newFileNonce(): ByteArray = ByteArray(FILE_NONCE_BYTES).also { SECURE_RANDOM.nextBytes(it) }

    /** Unique IV per chunk: 8 random file bytes plus a 32-bit chunk counter. */
    fun chunkIv(fileNonce: ByteArray, index: Long): ByteArray {
        require(fileNonce.size == FILE_NONCE_BYTES) { "File nonce must be 12 bytes" }
        require(index in 0..MAX_CHUNK_INDEX) { "Invalid chunk index: $index" }
        return ByteArray(FILE_NONCE_BYTES).also { iv ->
            System.arraycopy(fileNonce, 0, iv, 0, 8)
            writeIntBigEndian(iv, 8, index.toInt())
        }
    }

    /** Reserved counter value; never used by a plaintext chunk. */
    fun footerIv(fileNonce: ByteArray): ByteArray {
        require(fileNonce.size == FILE_NONCE_BYTES) { "File nonce must be 12 bytes" }
        return ByteArray(FILE_NONCE_BYTES).also { iv ->
            System.arraycopy(fileNonce, 0, iv, 0, 8)
            writeIntBigEndian(iv, 8, -1)
        }
    }

    /** Binds each chunk to the complete header, its index and its plaintext length. */
    fun chunkAad(headerBytes: ByteArray, index: Long, plaintextChunkLength: Int): ByteArray {
        require(index in 0..MAX_CHUNK_INDEX) { "Invalid chunk index: $index" }
        val output = ByteArrayOutputStream(headerBytes.size + 16)
        output.write(headerBytes)
        DataOutputStream(output).use { data ->
            data.writeLong(index)
            data.writeInt(plaintextChunkLength)
        }
        return output.toByteArray()
    }

    fun encodeHeader(metadata: Metadata): ByteArray {
        require(metadata.keyVersion > 0) { "Key version must be positive" }
        require(metadata.fileNonce.size == FILE_NONCE_BYTES) { "File nonce must be 12 bytes" }
        require(metadata.wrappedKey.iv.size == FILE_NONCE_BYTES) { "Wrapped key IV must be 12 bytes" }
        require(
            metadata.wrappedKey.ciphertextAndTag.size in
                (TAG_LENGTH_BYTES + 32)..1024
        ) { "Wrapped data key has an invalid length" }
        validateExtension(metadata.originalExtension)
        validateChunkSize(metadata.chunkSize)
        require(metadata.chunkCount == expectedChunkCount(metadata.plaintextLength, metadata.chunkSize)) {
            "Chunk count does not match plaintext length"
        }

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC_BYTES)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(metadata.keyVersion)
            data.write(uuidBytes(metadata.fileId))
            data.write(metadata.fileNonce)
            data.writeInt(mediaTypeCode(metadata.mediaType))
            val extension = metadata.originalExtension.toByteArray(StandardCharsets.US_ASCII)
            data.writeShort(extension.size)
            data.write(extension)
            data.writeLong(metadata.plaintextLength)
            data.writeInt(metadata.chunkSize)
            data.writeLong(metadata.chunkCount)
            data.write(metadata.wrappedKey.iv)
            data.writeInt(metadata.wrappedKey.ciphertextAndTag.size)
            data.write(metadata.wrappedKey.ciphertextAndTag)
        }
        return output.toByteArray()
    }

    /** Reads only a bounded prefix of [file], never the whole media payload. */
    fun readHeader(file: File): Header {
        RandomAccessFile(file, "r").use { input ->
            val prefix = ByteArray(MAX_HEADER_BYTES)
            val read = input.read(prefix)
            if (read < 0) throw ContainerFormatException("NGE1 file is empty")
            return readHeader(prefix.copyOf(read))
        }
    }

    fun readHeader(bytes: ByteArray): Header {
        val cursor = Cursor(bytes)
        val magic = cursor.read(MAGIC_BYTES.size)
        if (!magic.contentEquals(MAGIC_BYTES)) {
            throw ContainerFormatException("Invalid NGE1 magic")
        }

        val version = cursor.readInt()
        if (version != FORMAT_VERSION) {
            throw ContainerFormatException("Unsupported NGE1 format version: $version")
        }

        val keyVersion = cursor.readInt()
        if (keyVersion <= 0) throw ContainerFormatException("Invalid key version: $keyVersion")

        val fileId = uuidFrom(cursor.read(FILE_ID_BYTES))
        val fileNonce = cursor.read(FILE_NONCE_BYTES)

        val mediaType = mediaTypeFrom(cursor.readInt())
        val extensionLength = cursor.readUnsignedShort()
        if (extensionLength !in 1..MAX_EXTENSION_BYTES) {
            throw ContainerFormatException("Invalid extension length: $extensionLength")
        }
        val extension = String(cursor.read(extensionLength), StandardCharsets.US_ASCII)
        try {
            validateExtension(extension)
        } catch (e: IllegalArgumentException) {
            throw ContainerFormatException("Invalid extension in header", e)
        }

        val plaintextLength = cursor.readLong()
        if (plaintextLength <= 0) {
            throw ContainerFormatException("Invalid plaintext length: $plaintextLength")
        }

        val chunkSize = cursor.readInt()
        try {
            validateChunkSize(chunkSize)
        } catch (e: IllegalArgumentException) {
            throw ContainerFormatException("Invalid chunk size in header: $chunkSize", e)
        }

        val chunkCount = cursor.readLong()
        val expectedCount = try {
            expectedChunkCount(plaintextLength, chunkSize)
        } catch (e: IllegalArgumentException) {
            throw ContainerFormatException("Impossible chunk geometry in header", e)
        }
        if (chunkCount != expectedCount) {
            throw ContainerFormatException("Impossible chunk count: $chunkCount")
        }

        val wrappedIv = cursor.read(FILE_NONCE_BYTES)
        val wrappedLength = cursor.readInt()
        if (wrappedLength !in (TAG_LENGTH_BYTES + 32)..1024) {
            throw ContainerFormatException("Invalid wrapped data key length: $wrappedLength")
        }
        val wrappedBytes = cursor.read(wrappedLength)

        return Header(
            metadata = Metadata(
                keyVersion = keyVersion,
                fileId = fileId,
                fileNonce = fileNonce,
                mediaType = mediaType,
                originalExtension = extension,
                plaintextLength = plaintextLength,
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                wrappedKey = WrappedKey(wrappedIv, wrappedBytes)
            ),
            bytes = bytes.copyOf(cursor.position)
        )
    }

    private fun validateExtension(extension: String) {
        require(extension.length in 1..MAX_EXTENSION_BYTES) {
            "Extension must contain 1..$MAX_EXTENSION_BYTES characters"
        }
        require(extension.all { it.isLetterOrDigit() }) {
            "Extension must be alphanumeric"
        }
    }

    private fun mediaTypeCode(type: EvidenceType): Int = when (type) {
        EvidenceType.PHOTO -> 0
        EvidenceType.AUDIO -> 1
        EvidenceType.VIDEO -> 2
    }

    private fun mediaTypeFrom(code: Int): EvidenceType = when (code) {
        0 -> EvidenceType.PHOTO
        1 -> EvidenceType.AUDIO
        2 -> EvidenceType.VIDEO
        else -> throw ContainerFormatException("Unknown media type code: $code")
    }

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(FILE_ID_BYTES)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    private fun uuidFrom(bytes: ByteArray): UUID {
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    private fun writeIntBigEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private class Cursor(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        fun read(length: Int): ByteArray {
            if (length < 0 || position + length > bytes.size) {
                throw ContainerFormatException("Truncated NGE1 header")
            }
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun readInt(): Int = ByteBuffer.wrap(read(Int.SIZE_BYTES)).int

        fun readLong(): Long = ByteBuffer.wrap(read(Long.SIZE_BYTES)).long

        fun readUnsignedShort(): Int {
            val pair = read(2)
            return ((pair[0].toInt() and 0xFF) shl 8) or (pair[1].toInt() and 0xFF)
        }
    }
}
