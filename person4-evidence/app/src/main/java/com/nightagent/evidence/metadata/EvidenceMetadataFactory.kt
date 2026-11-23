package com.nightagent.evidence.metadata

import com.nightagent.evidence.crypto.ContainerFormatException
import com.nightagent.evidence.crypto.Nge1Container
import com.nightagent.evidence.integrity.ContainerDigestResult
import com.nightagent.evidence.integrity.ContainerDigests
import java.io.File
import java.io.IOException

/**
 * Builds persistent metadata from a published container without changing the
 * NGE1 format. The stable container file ID is reused as the metadata ID.
 */
object EvidenceMetadataFactory {

    fun fromPublishedContainer(
        filesDir: File,
        relativePath: String,
        nowMillis: () -> Long = { System.currentTimeMillis() }
    ): MetadataCreationResult {
        val container = EncryptedContainerPath.resolve(filesDir, relativePath)
            ?: return MetadataCreationResult.InvalidPath
        if (!container.exists()) return MetadataCreationResult.MissingFile
        if (!container.isFile) return MetadataCreationResult.UnreadableFile

        val header = try {
            Nge1Container.readHeader(container)
        } catch (e: ContainerFormatException) {
            return MetadataCreationResult.InvalidContainer
        } catch (e: IOException) {
            return MetadataCreationResult.UnreadableFile
        } catch (e: SecurityException) {
            return MetadataCreationResult.UnreadableFile
        }

        val size = try {
            container.length()
        } catch (e: SecurityException) {
            return MetadataCreationResult.UnreadableFile
        }
        if (size <= 0L) return MetadataCreationResult.UnreadableFile

        val digest = when (val result = ContainerDigests.ofFile(container)) {
            is ContainerDigestResult.Hashed -> result.hex
            ContainerDigestResult.Missing -> return MetadataCreationResult.MissingFile
            ContainerDigestResult.Unreadable -> return MetadataCreationResult.UnreadableFile
        }

        // NGE1 does not carry a capture timestamp. File modification time is
        // the closest available local signal; fall back to the supplied clock.
        val createdAt = try {
            val modified = container.lastModified()
            if (modified > 0L) modified else nowMillis()
        } catch (e: SecurityException) {
            nowMillis()
        }

        return MetadataCreationResult.Created(
            EvidenceMetadata(
                evidenceId = header.metadata.fileId.toString(),
                mediaType = header.metadata.mediaType,
                originalExtension = header.metadata.originalExtension,
                createdAtMillis = createdAt,
                containerRelativePath = relativePath,
                containerSizeBytes = size,
                sha256 = digest
            )
        )
    }
}
