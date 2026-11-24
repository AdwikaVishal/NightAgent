package com.nightagent.evidence.integration

import com.nightagent.evidence.consent.ConsentGate
import com.nightagent.evidence.consent.ConsentResult
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionStateProvider
import com.nightagent.evidence.permission.PermissionStatus

/**
 * Combined authorization layer.
 *
 * Application consent and Android permissions remain separate concepts and
 * are checked independently; this class only combines their outcomes into a
 * meaningful result for future capture/upload entry points.
 */
class EvidenceAuthorization(
    private val consentGate: ConsentGate,
    private val permissionStateProvider: PermissionStateProvider
) {

    suspend fun authorize(operation: EvidenceOperation): AuthorizationResult {
        when (val consent = consentGate.check(operation)) {
            is ConsentResult.Granted -> Unit
            is ConsentResult.Denied -> return AuthorizationResult.Denied(
                operation = operation,
                reason = consent.reason.toAuthorizationReason()
            )
        }

        val permissionStates = requiredPermissions(operation)
            .map(permissionStateProvider::stateOf)

        permissionStates.firstOrNull { it.status == PermissionStatus.PERMANENTLY_DENIED }
            ?.let {
                return AuthorizationResult.Denied(
                    operation,
                    AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED
                )
            }

        if (permissionStates.any { !it.isGranted }) {
            return AuthorizationResult.Denied(
                operation,
                AuthorizationResult.Reason.PERMISSION_MISSING
            )
        }

        return AuthorizationResult.Authorized
    }

    suspend fun isAuthorized(operation: EvidenceOperation): Boolean =
        authorize(operation) is AuthorizationResult.Authorized

    private fun requiredPermissions(operation: EvidenceOperation): Set<AppPermission> =
        when (operation) {
            EvidenceOperation.CAPTURE_PHOTO -> setOf(AppPermission.CAMERA)
            EvidenceOperation.CAPTURE_AUDIO -> setOf(AppPermission.RECORD_AUDIO)
            EvidenceOperation.CAPTURE_VIDEO ->
                setOf(AppPermission.CAMERA, AppPermission.RECORD_AUDIO)

            EvidenceOperation.CLOUD_UPLOAD -> emptySet()
        }

    private fun ConsentResult.Reason.toAuthorizationReason(): AuthorizationResult.Reason =
        when (this) {
            ConsentResult.Reason.NOT_GRANTED -> AuthorizationResult.Reason.CONSENT_NOT_GRANTED
            ConsentResult.Reason.REVOKED -> AuthorizationResult.Reason.CONSENT_REVOKED
            ConsentResult.Reason.OUTDATED_VERSION -> AuthorizationResult.Reason.CONSENT_OUTDATED
        }
}
