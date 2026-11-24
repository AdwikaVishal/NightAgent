package com.nightagent.evidence.permission

/**
 * Android runtime permissions used by this phase.
 *
 * The platform names are kept as literals so model/authorization code can be
 * unit tested without the Android framework.
 */
enum class AppPermission(val platformName: String) {
    CAMERA("android.permission.CAMERA"),
    RECORD_AUDIO("android.permission.RECORD_AUDIO");

    companion object {
        fun fromPlatformName(name: String?): AppPermission? =
            entries.firstOrNull { it.platformName == name }
    }
}
