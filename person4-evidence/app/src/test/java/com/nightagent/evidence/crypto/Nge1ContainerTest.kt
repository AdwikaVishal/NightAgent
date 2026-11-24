package com.nightagent.evidence.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Focused checks for the self-describing NGE1 header helpers. */
class Nge1ContainerTest {

    @Test
    fun chunkIvsAreUniqueAndFooterUsesReservedCounter() {
        val nonce = Nge1Container.newFileNonce()

        val first = Nge1Container.chunkIv(nonce, 0L)
        val second = Nge1Container.chunkIv(nonce, 1L)
        val footer = Nge1Container.footerIv(nonce)

        assertEquals(12, first.size)
        assertFalse(first.contentEquals(second))
        assertFalse(first.contentEquals(footer))
    }

    @Test
    fun maximumChunkIndexDoesNotCollideWithFooterIv() {
        val nonce = Nge1Container.newFileNonce()
        val highestChunk = Nge1Container.chunkIv(nonce, Nge1Container.MAX_CHUNK_INDEX)
        val footer = Nge1Container.footerIv(nonce)

        assertFalse(highestChunk.contentEquals(footer))
        assertThrows(IllegalArgumentException::class.java) {
            Nge1Container.chunkIv(nonce, Nge1Container.RESERVED_CHUNK_INDEX)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Nge1Container.chunkIv(nonce, -1L)
        }
    }

    @Test
    fun nonceGenerationIsUnique() {
        val nonces = (0 until 64).map { Nge1Container.newFileNonce() }

        assertEquals(12, nonces.first().size)
        assertEquals(nonces.size, nonces.map { it.toList() }.distinct().size)
        assertTrue(nonces.all { it.any { byte -> byte != 0.toByte() } })
    }

    @Test
    fun containerLengthRejectsOverflow() {
        val key = TestMasterKey.seeded(0x43)
        val metadata = Nge1Container.Metadata(
            keyVersion = key.version,
            fileId = UUID.randomUUID(),
            fileNonce = Nge1Container.newFileNonce(),
            mediaType = com.nightagent.evidence.model.EvidenceType.VIDEO,
            originalExtension = "mp4",
            plaintextLength = Long.MAX_VALUE,
            chunkSize = 7,
            chunkCount = 1L,
            wrappedKey = key.wrap(ByteArray(32))
        )

        assertThrows(ContainerFormatException::class.java) {
            Nge1Container.containerLength(128, metadata)
        }
    }

    @Test
    fun expectedChunkCountRejectsImpossibleGeometry() {
        assertEquals(4L, Nge1Container.expectedChunkCount(25L, 7))
        assertEquals(1L, Nge1Container.expectedChunkCount(7L, 7))

        assertThrows(IllegalArgumentException::class.java) {
            Nge1Container.expectedChunkCount(0L, 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Nge1Container.expectedChunkCount(25L, 0)
        }
    }

    @Test
    fun chunkAadChangesWithHeaderIndexAndLength() {
        val header = ByteArray(32) { it.toByte() }
        val baseline = Nge1Container.chunkAad(header, 0L, 8)
        val changedHeader = header.copyOf().also { it[0] = 99 }
        val changedIndex = Nge1Container.chunkAad(header, 1L, 8)
        val changedLength = Nge1Container.chunkAad(header, 0L, 9)

        assertArrayEquals(baseline, Nge1Container.chunkAad(header, 0L, 8))
        assertFalse(baseline.contentEquals(Nge1Container.chunkAad(changedHeader, 0L, 8)))
        assertFalse(baseline.contentEquals(changedIndex))
        assertFalse(baseline.contentEquals(changedLength))
    }

    @Test
    fun encodeThenReadHeaderPreservesMetadata() {
        val key = TestMasterKey.seeded(0x42)
        val dataKey = ByteArray(32) { it.toByte() }
        val wrapped = key.wrap(dataKey)
        val metadata = Nge1Container.Metadata(
            keyVersion = key.version,
            fileId = UUID.randomUUID(),
            fileNonce = Nge1Container.newFileNonce(),
            mediaType = com.nightagent.evidence.model.EvidenceType.AUDIO,
            originalExtension = "m4a",
            plaintextLength = 25L,
            chunkSize = 7,
            chunkCount = 4L,
            wrappedKey = wrapped
        )

        val parsed = Nge1Container.readHeader(Nge1Container.encodeHeader(metadata)).metadata

        assertEquals(metadata.fileId, parsed.fileId)
        assertArrayEquals(metadata.fileNonce, parsed.fileNonce)
        assertEquals(metadata.mediaType, parsed.mediaType)
        assertEquals(metadata.originalExtension, parsed.originalExtension)
        assertEquals(metadata.plaintextLength, parsed.plaintextLength)
        assertEquals(metadata.chunkSize, parsed.chunkSize)
        assertEquals(metadata.chunkCount, parsed.chunkCount)
        assertArrayEquals(wrapped.iv, parsed.wrappedKey.iv)
        assertArrayEquals(wrapped.ciphertextAndTag, parsed.wrappedKey.ciphertextAndTag)
    }
}
