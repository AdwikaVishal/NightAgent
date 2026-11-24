package com.nightagent.evidence.integration

import com.nightagent.evidence.model.EvidenceType

/** Who asked for a capture. SOS requests still go through normal authorization. */
enum class TriggerSource {
    HOST_APP,
    SOS,
    SOS_MOCK
}

data class CaptureTriggerRequest(
    val type: EvidenceType,
    val source: TriggerSource = TriggerSource.HOST_APP,
    val externalRequestId: String? = null
)

sealed interface CaptureTriggerResult {
    data object Accepted : CaptureTriggerResult
    data class Denied(val reason: AuthorizationResult.Reason) : CaptureTriggerResult

    /** The host must surface foreground capture UI; no silent background capture. */
    data class Unsupported(val reason: String) : CaptureTriggerResult
}

/**
 * Minimal trigger boundary the main app can call for SOS or other automation.
 *
 * Implementations must respect Android foreground-service and background
 * camera/microphone restrictions; this module does not grant background access.
 */
fun interface EvidenceCaptureTrigger {
    suspend fun trigger(request: CaptureTriggerRequest): CaptureTriggerResult
}

/** Test/host trigger that records requests and never starts background capture. */
class MockEvidenceCaptureTrigger : EvidenceCaptureTrigger {

    private val requestsList = mutableListOf<CaptureTriggerRequest>()
    val requests: List<CaptureTriggerRequest> get() = requestsList

    override suspend fun trigger(request: CaptureTriggerRequest): CaptureTriggerResult {
        requestsList += request
        return CaptureTriggerResult.Unsupported(
            "Background camera/microphone capture is not supported; host app must show foreground UI"
        )
    }
}
