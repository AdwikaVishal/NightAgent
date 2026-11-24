package com.nightagent.evidence.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/** Handles explicit permission requests and the permanent-denial escape hatch. */
interface PermissionRequester {
    fun request(permissions: Set<AppPermission>, onResult: (Map<AppPermission, Boolean>) -> Unit)

    fun openAppSettings()
}

/**
 * Compose-compatible implementation.
 *
 * Registration happens through rememberLauncherForActivityResult, which keeps
 * the launcher tied to the current activity/registry. Nothing is launched
 * automatically — callers must invoke [PermissionRequester.request] after an
 * explicit user action.
 */
@Composable
fun rememberPermissionRequester(): PermissionRequester {
    val context = LocalContext.current
    val requestStore = remember(context) { PermissionRequestStore.create(context) }
    val pendingCallback = remember { mutableStateOf<((Map<AppPermission, Boolean>) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val mapped = result.mapNotNull { (name, granted) ->
            AppPermission.fromPlatformName(name)?.to(granted)
        }.toMap()

        val callback = pendingCallback.value
        pendingCallback.value = null
        callback?.invoke(mapped)
    }

    return remember(launcher, context, requestStore) {
        object : PermissionRequester {
            override fun request(
                permissions: Set<AppPermission>,
                onResult: (Map<AppPermission, Boolean>) -> Unit
            ) {
                if (permissions.isEmpty()) {
                    onResult(emptyMap())
                    return
                }
                pendingCallback.value = onResult
                permissions.forEach(requestStore::markRequested)
                launcher.launch(permissions.map { it.platformName }.toTypedArray())
            }

            override fun openAppSettings() {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
