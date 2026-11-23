package com.nightagent.evidence.model

/**
 * Identifies one independently stored consent decision.
 *
 * Capture consent is keyed by [EvidenceType] (PHOTO, AUDIO and VIDEO are
 * independent). Cloud upload consent has no evidence type and is a single
 * global decision.
 */
data class ConsentKey(
    val consentType: ConsentType,
    val evidenceType: EvidenceType? = null
) {
    /** Stable string used as the DataStore preference prefix. */
    val storageKey: String
        get() = if (consentType == ConsentType.CAPTURE) {
            "capture.${evidenceType?.name ?: "UNKNOWN"}"
        } else {
            "cloud_upload"
        }

    companion object {
        fun capture(type: EvidenceType): ConsentKey = ConsentKey(ConsentType.CAPTURE, type)

        val CLOUD_UPLOAD: ConsentKey = ConsentKey(ConsentType.CLOUD_UPLOAD, null)

        /** All consent decisions managed by this module. */
        val ALL: List<ConsentKey> = listOf(
            capture(EvidenceType.PHOTO),
            capture(EvidenceType.AUDIO),
            capture(EvidenceType.VIDEO),
            CLOUD_UPLOAD
        )
    }
}
