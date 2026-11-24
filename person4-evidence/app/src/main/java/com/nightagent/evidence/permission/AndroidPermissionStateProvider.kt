package com.nightagent.evidence.permission

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Android implementation of permission status checking. */
class AndroidPermissionStateProvider(
    private val context: Context,
    private val requestStore: PermissionRequestStore = PermissionRequestStore.create(context)
) : PermissionStateProvider {

    override fun stateOf(permission: AppPermission): PermissionState {
        val granted = ContextCompat.checkSelfPermission(
            context,
            permission.platformName
        ) == PackageManager.PERMISSION_GRANTED

        val status = when {
            granted -> PermissionStatus.GRANTED

            requestStore.wasRequested(permission) &&
                context is Activity &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    context,
                    permission.platformName
                ) -> PermissionStatus.PERMANENTLY_DENIED

            else -> PermissionStatus.DENIED
        }

        return PermissionState(permission, status)
    }
}
