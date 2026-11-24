package com.example.nightagent.sos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles actions from the ongoing SOS notification (e.g. "Stop SOS").
 */
class SOSActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_SOS = "com.example.nightagent.ACTION_STOP_SOS"
        const val ACTION_CANCEL_SOS = "com.example.nightagent.ACTION_CANCEL_SOS"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP_SOS -> {
                Log.d("SOSActionReceiver", "Stop SOS requested from notification action")
                SOSManager.resolve(context)
            }
            ACTION_CANCEL_SOS -> {
                Log.d("SOSActionReceiver", "Cancel SOS requested from notification action")
                TriggerManager.cancel(context, "Cancelled by user")
            }
        }
    }
}
