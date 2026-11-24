package com.example.nightagent.sos

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import com.example.nightagent.model.TriggerType

class PowerButtonReceiver : BroadcastReceiver() {

    private var pressCount = 0
    private var lastPressTime = 0L

    companion object {
        private const val PRESS_WINDOW_MS = 2000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        SafetySettings.init(context)

        // Toggle check
        if (!SafetySettings.powerButtonSOS.value) return

        val now = System.currentTimeMillis()

        if (now - lastPressTime < PRESS_WINDOW_MS) {
            pressCount++
        } else {
            pressCount = 1
        }

        lastPressTime = now

        if (pressCount >= 2) {
            pressCount = 0
            TriggerManager.onTrigger(context, TriggerType.POWER_BUTTON)
        }
    }
}
