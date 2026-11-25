package com.example.nightagent.firebase

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.nightagent.guardian.repository.GuardianRepositoryImpl
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Acknowledge / Escalate actions on an `sos_alert` notification.
 *
 * Both actions go through the trusted callables, which verify that the caller
 * is linked to the SOS owner and write the canonical session state observed by
 * the owner's app. The broadcast is explicit and not exported.
 */
class SosAlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val ownerUid = intent.getStringExtra(EXTRA_OWNER_UID)
        if (sessionId.isNullOrBlank() || ownerUid.isNullOrBlank()) {
            Log.w(TAG, "SOS alert action missing session or owner")
            return
        }
        val pendingResult = goAsync()
        scope.launch(Dispatchers.IO) {
            try {
                val guardianUid = FirebaseAuth.getInstance().currentUser?.uid
                val repository = GuardianRepositoryImpl(context.applicationContext)
                val result = when (intent.action) {
                    ACTION_ACKNOWLEDGE ->
                        repository.acknowledgeEmergency(sessionId, ownerUid, guardianUid)
                    ACTION_ESCALATE ->
                        repository.escalateEmergency(sessionId, ownerUid)
                    else -> return@launch
                }
                if (result is com.example.nightagent.guardian.domain.repository.GuardianRepository.ListResult.Error) {
                    Log.w(TAG, "SOS alert action failed: ${result.message}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SosAlertActionReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val ACTION_ACKNOWLEDGE = "com.example.nightagent.SOS_ACKNOWLEDGE"
        const val ACTION_ESCALATE = "com.example.nightagent.SOS_ESCALATE"
        const val EXTRA_SESSION_ID = "sosSessionId"
        const val EXTRA_OWNER_UID = "ownerUid"

        fun actionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            ownerUid: String
        ): PendingIntent {
            val intent = Intent(context, SosAlertActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_OWNER_UID, ownerUid)
            }
            return PendingIntent.getBroadcast(
                context,
                "${action}_$sessionId".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
