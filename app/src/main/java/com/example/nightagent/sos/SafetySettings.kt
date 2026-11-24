package com.example.nightagent.sos

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

object SafetySettings {

    private const val PREFS_NAME = "nightagent_safety_settings"
    private var prefs: SharedPreferences? = null

    /**
     * Custom MutableState implementation that delegates to SharedPreferences
     * when initialized, while seamlessly functioning as a standard Compose MutableState.
     */
    private class PersistedBooleanState(
        private val key: String,
        private val defaultValue: Boolean
    ) : MutableState<Boolean> {
        private val state: MutableState<Boolean> = mutableStateOf(defaultValue)

        fun loadFromPrefs(sharedPreferences: SharedPreferences) {
            state.value = sharedPreferences.getBoolean(key, defaultValue)
        }

        override var value: Boolean
            get() = state.value
            set(newVal) {
                state.value = newVal
                prefs?.edit()?.putBoolean(key, newVal)?.apply()
            }

        override fun component1(): Boolean = state.value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }

    // Safety Screen toggles (persisted)
    private val _voiceSOS = PersistedBooleanState("voice_sos", false)
    val voiceSOS: MutableState<Boolean> = _voiceSOS

    private val _shakeDetection = PersistedBooleanState("shake_detection", false)
    val shakeDetection: MutableState<Boolean> = _shakeDetection

    private val _powerButtonSOS = PersistedBooleanState("power_button_sos", false)
    val powerButtonSOS: MutableState<Boolean> = _powerButtonSOS

    private val _silentMode = PersistedBooleanState("silent_mode", false)
    val silentMode: MutableState<Boolean> = _silentMode

    private val _autoRecording = PersistedBooleanState("auto_recording", false)
    val autoRecording: MutableState<Boolean> = _autoRecording

    // Settings Screen toggles (persisted)
    private val _pushNotifications = PersistedBooleanState("push_notifications", true)
    val pushNotifications: MutableState<Boolean> = _pushNotifications

    private val _darkMode = PersistedBooleanState("dark_mode", false)
    val darkMode: MutableState<Boolean> = _darkMode

    private val _locationSharing = PersistedBooleanState("location_sharing", true)
    val locationSharing: MutableState<Boolean> = _locationSharing

    private val _autoRecordingConsent = PersistedBooleanState("auto_recording_consent", true)
    val autoRecordingConsent: MutableState<Boolean> = _autoRecordingConsent

    /**
     * Initializes the settings from SharedPreferences so toggles survive process death.
     */
    fun init(context: Context) {
        if (prefs != null) return
        try {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _voiceSOS.loadFromPrefs(sp)
            _shakeDetection.loadFromPrefs(sp)
            _powerButtonSOS.loadFromPrefs(sp)
            _silentMode.loadFromPrefs(sp)
            _autoRecording.loadFromPrefs(sp)
            _pushNotifications.loadFromPrefs(sp)
            _darkMode.loadFromPrefs(sp)
            _locationSharing.loadFromPrefs(sp)
            _autoRecordingConsent.loadFromPrefs(sp)
        } catch (_: Exception) {
            // Gracefully ignore in JVM unit tests
        }
    }

    /**
     * Reset for unit testing.
     */
    internal fun resetForTesting(
        shake: Boolean = false,
        voice: Boolean = false,
        power: Boolean = false,
        silent: Boolean = false,
        autoRecord: Boolean = false
    ) {
        // Set a dummy SharedPreferences so init() does not overwrite explicit test values
        val dummyPrefs = object : SharedPreferences {
            override fun getAll(): Map<String, *> = emptyMap<String, Any>()
            override fun getString(key: String?, defValue: String?): String? = defValue
            override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = false
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(k: String?, v: String?) = this
                override fun putStringSet(k: String?, v: Set<String>?) = this
                override fun putInt(k: String?, v: Int) = this
                override fun putLong(k: String?, v: Long) = this
                override fun putFloat(k: String?, v: Float) = this
                override fun putBoolean(k: String?, v: Boolean) = this
                override fun remove(k: String?) = this
                override fun clear() = this
                override fun commit() = true
                override fun apply() {}
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        prefs = dummyPrefs
        _voiceSOS.value = voice
        _shakeDetection.value = shake
        _powerButtonSOS.value = power
        _silentMode.value = silent
        _autoRecording.value = autoRecord
        _pushNotifications.value = true
        _darkMode.value = false
        _locationSharing.value = true
        _autoRecordingConsent.value = true
    }
}
