package com.nightagent.evidence.metadata

/** Outcome of deriving metadata from an already published NGE1 container. */
sealed interface MetadataCreationResult {
    data class Created(val metadata: EvidenceMetadata) : MetadataCreationResult
    data object InvalidPath : MetadataCreationResult
    data object MissingFile : MetadataCreationResult
    data object UnreadableFile : MetadataCreationResult
    data object InvalidContainer : MetadataCreationResult
}
