package com.nightagent.evidence.permission

data class PermissionState(
    val permission: AppPermission,
    val status: PermissionStatus
) {
    val isGranted: Boolean get() = status == PermissionStatus.GRANTED
}

enum class PermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED
}
