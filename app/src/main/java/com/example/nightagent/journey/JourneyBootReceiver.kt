package com.example.nightagent.journey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JourneyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val appCtx = context.applicationContext
                val store = JourneyRuntimeStore(appCtx)
                if (store.getActiveJourneyId() != null) {
                    val fine = androidx.core.content.ContextCompat.checkSelfPermission(appCtx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val coarse = androidx.core.content.ContextCompat.checkSelfPermission(appCtx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) {
                        JourneyTrackingService.start(appCtx)
                    } else {
                        android.util.Log.w("JourneyBootReceiver", "Active journey found but location permission missing; cannot restart tracking service")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("JourneyBootReceiver", "Failed to restore journey tracking on boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
