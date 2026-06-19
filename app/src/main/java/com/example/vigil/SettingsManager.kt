package com.example.vigil

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "vigil_settings"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_SAVE_FULL_RES = "save_full_resolution"
        private const val DEFAULT_QUALITY = 95
        private const val DEFAULT_SAVE_FULL = true
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var imageQuality: Int
        get() = prefs.getInt(KEY_IMAGE_QUALITY, DEFAULT_QUALITY)
        set(value) = prefs.edit().putInt(KEY_IMAGE_QUALITY, value.coerceIn(1, 100)).apply()
    
    var saveFullResolution: Boolean
        get() = prefs.getBoolean(KEY_SAVE_FULL_RES, DEFAULT_SAVE_FULL)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_FULL_RES, value).apply()
}