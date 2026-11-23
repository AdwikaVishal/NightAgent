package com.nightagent.evidence.model

/** Operations that require an authorization decision before they may run. */
enum class EvidenceOperation {
    CAPTURE_PHOTO,
    CAPTURE_AUDIO,
    CAPTURE_VIDEO,
    CLOUD_UPLOAD;

    /** The consent decision that governs this operation. */
    val consentKey: ConsentKey
        get() = when (this) {
            CAPTURE_PHOTO -> ConsentKey.capture(EvidenceType.PHOTO)
            CAPTURE_AUDIO -> ConsentKey.capture(EvidenceType.AUDIO)
            CAPTURE_VIDEO -> ConsentKey.capture(EvidenceType.VIDEO)
            CLOUD_UPLOAD -> ConsentKey.CLOUD_UPLOAD
        }

    companion object {
        fun forCapture(type: EvidenceType): EvidenceOperation = when (type) {
            EvidenceType.PHOTO -> CAPTURE_PHOTO
            EvidenceType.AUDIO -> CAPTURE_AUDIO
            EvidenceType.VIDEO -> CAPTURE_VIDEO
        }
    }
}
