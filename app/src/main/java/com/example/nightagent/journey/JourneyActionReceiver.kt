package com.example.nightagent.journey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JourneyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val journeyId = intent.getStringExtra(JourneyNotificationHelper.EXTRA_JOURNEY_ID)
        val checkInId = intent.getStringExtra(JourneyNotificationHelper.EXTRA_CHECKIN_ID)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val engine = JourneyEngine.getInstance(context.applicationContext)
                when (action) {
                    JourneyNotificationHelper.ACTION_CHECKIN_SAFE -> if (journeyId != null && checkInId != null) engine.acknowledgeCheckIn(journeyId, checkInId)
                    JourneyNotificationHelper.ACTION_CHECKIN_HELP -> if (journeyId != null && checkInId != null) engine.requestHelpOnCheckIn(journeyId, checkInId)
                    JourneyNotificationHelper.ACTION_END_JOURNEY -> engine.completeJourney("User ended journey from notification")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
