package com.nightagent.evidence.testutil

import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.MetadataCreationResult
import com.nightagent.evidence.model.EvidenceType

/** Test factory for storage paths that need deterministic metadata failures. */
object FakeMetadataFactory {

    fun created(
        evidenceId: String,
        mediaType: EvidenceType,
        originalExtension: String,
        digest: String = "0".repeat(64)
    ): (String) -> MetadataCreationResult = { relativePath ->
        MetadataCreationResult.Created(
            EvidenceMetadata(
                evidenceId = evidenceId,
                mediaType = mediaType,
                originalExtension = originalExtension,
                createdAtMillis = 1_700_000_000_000L,
                containerRelativePath = relativePath,
                containerSizeBytes = 128L,
                sha256 = digest
            )
        )
    }

    fun failing(result: MetadataCreationResult): (String) -> MetadataCreationResult = { result }
}
