package com.nightagent.evidence.integration

import com.nightagent.evidence.model.EvidenceOperation

/**
 * Small adapter for operations that need more than one consent decision.
 *
 * [EvidenceAuthorization] deliberately authorizes one operation at a time. A
 * video with microphone audio is both CAPTURE_VIDEO and CAPTURE_AUDIO, so this
 * helper checks both through the existing authorization layer and denies the
 * start if either check fails. It never grants consent or skips a permission
 * check, and it has no role in stopping an already active recording.
 */
class CombinedEvidenceAuthorization(
    private val authorization: EvidenceAuthorization
) {

    suspend fun authorizeVideoWithAudio(): AuthorizationResult {
        val video = authorization.authorize(EvidenceOperation.CAPTURE_VIDEO)
        if (video is AuthorizationResult.Denied) return video

        return authorization.authorize(EvidenceOperation.CAPTURE_AUDIO)
    }
}
