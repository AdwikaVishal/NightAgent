package com.nightagent.evidence.permission

import android.content.Context

/**
 * Remembers which permissions have already been requested. Android's
 * shouldShowRequestPermissionRationale() returns false both before the first
 * request and after a permanent denial, so request history disambiguates them.
 */
interface PermissionRequestStore {
    fun markRequested(permission: AppPermission)
    fun wasRequested(permission: AppPermission): Boolean

    companion object {
        fun create(context: Context): PermissionRequestStore =
            SharedPreferencesPermissionRequestStore(context.applicationContext)
    }
}

private class SharedPreferencesPermissionRequestStore(
    context: Context
) : PermissionRequestStore {

    private val preferences = context.getSharedPreferences(
        "evidence_permission_requests",
        Context.MODE_PRIVATE
    )

    override fun markRequested(permission: AppPermission) {
        preferences.edit()
            .putBoolean(permission.platformName, true)
            .commit()
    }

    override fun wasRequested(permission: AppPermission): Boolean =
        preferences.getBoolean(permission.platformName, false)
}
