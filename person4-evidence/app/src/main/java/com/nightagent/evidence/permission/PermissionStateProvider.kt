package com.nightagent.evidence.permission

/** Read-only view of current Android permission status. */
interface PermissionStateProvider {
    fun stateOf(permission: AppPermission): PermissionState
}
