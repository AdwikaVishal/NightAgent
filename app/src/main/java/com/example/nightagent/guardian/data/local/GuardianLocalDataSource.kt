package com.example.nightagent.guardian.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.*

object GuardianLocalDataSource {

    private const val PREFS_NAME = "guardian_prefs"
    private const val MAX_GUARDIANS = 5
    private const val KEY_GUARDIANS = "guardians_list"
    private const val KEY_PRIMARY_ID = "primary_guardian_id"

    private val gson = Gson()

    fun getGuardians(context: Context): List<Guardian> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_GUARDIANS, null)
        if (json == null) return emptyList()

        return try {
            val type = object : TypeToken<List<GuardianEntity>>() {}.type
            val entities: List<GuardianEntity> = gson.fromJson(json, type)
            entities.map { it.toDomain() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveGuardians(context: Context, guardians: List<Guardian>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val guardianEntities = guardians.map { GuardianEntity.fromDomain(it) }
        val json = gson.toJson(guardianEntities)
        prefs.edit().putString(KEY_GUARDIANS, json).apply()
    }

    fun setPrimaryGuardian(context: Context, guardianId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PRIMARY_ID, guardianId ?: "").apply()
    }

    fun getPrimaryGuardianId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_PRIMARY_ID, null)
        return if (id.isNullOrEmpty()) null else id
    }

    fun normalizePhoneNumber(phone: String): String {
        var normalized = phone
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
        if (normalized.startsWith("+91")) {
            normalized = normalized.substring(3)
        }
        return normalized
    }

    fun validatePhone(phone: String): Boolean {
        return phone.matches(Regex("^\\+?[1-9]\\d{1,14}$")) && phone.length >= 8
    }

    fun validateName(name: String): Boolean {
        return name.trim().length >= 2 && name.trim().length <= 50
    }

    data class GuardianEntity(
        val guardianId: String,
        val userId: String?,
        val name: String,
        val phoneNumber: String,
        val email: String?,
        val relationship: Relationship,
        val isPrimary: Boolean,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        fun toDomain(): Guardian {
            return Guardian(
                guardianId = guardianId,
                userId = userId,
                name = name,
                phoneNumber = phoneNumber,
                email = email,
                relationship = relationship,
                isPrimary = isPrimary,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }

        companion object {
            fun fromDomain(guardian: Guardian): GuardianEntity {
                return GuardianEntity(
                    guardianId = guardian.guardianId,
                    userId = guardian.userId,
                    name = guardian.name,
                    phoneNumber = guardian.phoneNumber,
                    email = guardian.email,
                    relationship = guardian.relationship,
                    isPrimary = guardian.isPrimary,
                    createdAt = guardian.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }
}