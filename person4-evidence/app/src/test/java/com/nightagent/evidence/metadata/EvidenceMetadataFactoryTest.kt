package com.nightagent.evidence.metadata

import com.nightagent.evidence.crypto.EvidenceEncryptionEngine
import com.nightagent.evidence.crypto.JvmAtomicFilePublisher
import com.nightagent.evidence.crypto.TestKeyAccess
import com.nightagent.evidence.crypto.TestMasterKey
import com.nightagent.evidence.integrity.ContainerDigests
import com.nightagent.evidence.model.EvidenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Metadata derivation from published NGE1 containers. */
class EvidenceMetadataFactoryTest {

    @Test
    fun publishedContainerProducesMetadataWithStableNge1Id() {
        val filesDir = Files.createTempDirectory("metadata_factory").toFile()
        val plaintext = File(filesDir, "capture.jpg").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        val relativePath = "evidence-encrypted/photos/evidence.ng1"
        val target = File(filesDir, relativePath)
        target.parentFile?.mkdirs()

        val encryption = realEngine().encrypt(plaintext, target, EvidenceType.PHOTO)

        val result = EvidenceMetadataFactory.fromPublishedContainer(filesDir, relativePath)
        assertTrue(result is MetadataCreationResult.Created)
        val metadata = (result as MetadataCreationResult.Created).metadata

        assertEquals(encryption.fileId.toString(), metadata.evidenceId)
        assertEquals(EvidenceType.PHOTO, metadata.mediaType)
        assertEquals("jpg", metadata.originalExtension)
        assertEquals(relativePath, metadata.containerRelativePath)
        assertEquals(target.length(), metadata.containerSizeBytes)
        assertEquals(ContainerDigests.sha256Hex(target.readBytes()), metadata.sha256)
        assertEquals(EvidenceMetadata.CURRENT_SCHEMA_VERSION, metadata.schemaVersion)
        assertEquals(RegistrationStatus.STORED, metadata.registrationStatus)
        assertFalse(metadata.containerRelativePath.contains("capture.jpg"))
    }

    @Test
    fun missingContainerIsReported() {
        val filesDir = Files.createTempDirectory("metadata_factory_missing").toFile()
        assertEquals(
            MetadataCreationResult.MissingFile,
            EvidenceMetadataFactory.fromPublishedContainer(
                filesDir,
                "evidence-encrypted/photos/absent.ng1"
            )
        )
    }

    @Test
    fun nonContainerAndPlaintextPathsAreRejected() {
        val filesDir = Files.createTempDirectory("metadata_factory_invalid").toFile()

        assertEquals(
            MetadataCreationResult.InvalidPath,
            EvidenceMetadataFactory.fromPublishedContainer(filesDir, "capture.jpg")
        )
        val absoluteOutside = File(filesDir, "outside.ng1").absolutePath
        assertEquals(
            MetadataCreationResult.InvalidPath,
            EvidenceMetadataFactory.fromPublishedContainer(filesDir, absoluteOutside)
        )
        assertEquals(
            MetadataCreationResult.InvalidPath,
            EvidenceMetadataFactory.fromPublishedContainer(filesDir, "../escape.ng1")
        )
    }

    @Test
    fun malformedContainerIsRejected() {
        val filesDir = Files.createTempDirectory("metadata_factory_malformed").toFile()
        val relativePath = "evidence-encrypted/audio/broken.ng1"
        val target = File(filesDir, relativePath)
        target.parentFile?.mkdirs()
        target.writeText("not an NGE1 container")

        assertEquals(
            MetadataCreationResult.InvalidContainer,
            EvidenceMetadataFactory.fromPublishedContainer(filesDir, relativePath)
        )
    }

    private fun realEngine(): EvidenceEncryptionEngine = EvidenceEncryptionEngine(
        TestKeyAccess(listOf(TestMasterKey.seeded(0x55))),
        JvmAtomicFilePublisher()
    )
}
