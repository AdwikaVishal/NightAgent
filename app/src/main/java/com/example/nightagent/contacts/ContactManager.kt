package com.example.nightagent.contacts

import android.content.Context
import com.example.nightagent.ui.screens.EmergencyContact

object ContactManager {

    private const val PREFS = "emergency_contacts"

    fun saveContacts(context: Context, contacts: List<EmergencyContact>) {

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val stored = contacts.map {
            "${it.name}|${it.phone}"
        }.toSet()

        prefs.edit().putStringSet("numbers", stored).apply()
    }

    fun getContacts(context: Context): List<EmergencyContact> {

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val stored = prefs.getStringSet("numbers", emptySet()) ?: emptySet<String>()

        return stored.map {

            val parts = it.split("|", limit = 2)

            if (parts.size < 2 || parts[1].isBlank()) {
                return@map EmergencyContact(name = parts[0], phone = "", isPriority = false)
            }

            EmergencyContact(
                name = parts[0].trim(),
                phone = parts[1].trim(),
                isPriority = false
            )
        }
    }
}