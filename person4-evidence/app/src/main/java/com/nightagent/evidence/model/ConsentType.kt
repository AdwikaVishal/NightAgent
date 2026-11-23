package com.nightagent.evidence.model

/**
 * Consent categories. Capture consent and cloud upload consent are deliberately
 * separate: allowing capture never implies permission to upload to the cloud.
 */
enum class ConsentType {
    CAPTURE,
    CLOUD_UPLOAD
}
