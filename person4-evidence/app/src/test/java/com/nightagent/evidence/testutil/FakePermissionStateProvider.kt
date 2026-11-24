package com.nightagent.evidence.testutil

import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionState
import com.nightagent.evidence.permission.PermissionStateProvider
import com.nightagent.evidence.permission.PermissionStatus

/** Fake permission provider; every permission defaults to DENIED. */
class FakePermissionStateProvider(
    statuses: Map<AppPermission, PermissionStatus> = emptyMap()
) : PermissionStateProvider {

    private val statuses = statuses.toMutableMap()

    override fun stateOf(permission: AppPermission): PermissionState =
        PermissionState(
            permission = permission,
            status = statuses[permission] ?: PermissionStatus.DENIED
        )

    fun set(permission: AppPermission, status: PermissionStatus) {
        statuses[permission] = status
    }
}
