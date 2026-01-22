package com.bitchat.android.features.festival

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FestivalModeManager - Handles festival mode toggle and persistence
 * 
 * Android equivalent of iOS FestivalModeManager.swift
 * Uses SharedPreferences to persist the festival mode setting.
 */
class FestivalModeManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "festival_prefs"
        private const val KEY_FESTIVAL_MODE_ENABLED = "festival_mode_enabled"
        private const val KEY_SHARE_LOCATION = "share_location_enabled"
        private const val KEY_LAST_JOINED_CHANNEL = "last_joined_channel"
        
        @Volatile
        private var instance: FestivalModeManager? = null
        
        fun getInstance(context: Context): FestivalModeManager {
            return instance ?: synchronized(this) {
                instance ?: FestivalModeManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ========================================================================
    // MARK: - Festival Mode Toggle
    // ========================================================================
    
    private val _isFestivalModeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_FESTIVAL_MODE_ENABLED, false)
    )
    val isFestivalModeEnabled: StateFlow<Boolean> = _isFestivalModeEnabled.asStateFlow()
    
    /**
     * Enable or disable festival mode
     */
    fun setFestivalModeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FESTIVAL_MODE_ENABLED, enabled) }
        _isFestivalModeEnabled.value = enabled
    }
    
    /**
     * Toggle festival mode
     */
    fun toggleFestivalMode(): Boolean {
        val newValue = !_isFestivalModeEnabled.value
        setFestivalModeEnabled(newValue)
        return newValue
    }
    
    // ========================================================================
    // MARK: - Location Sharing
    // ========================================================================
    
    private val _isLocationSharingEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SHARE_LOCATION, false)
    )
    val isLocationSharingEnabled: StateFlow<Boolean> = _isLocationSharingEnabled.asStateFlow()
    
    /**
     * Enable or disable location sharing with friends
     */
    fun setLocationSharingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHARE_LOCATION, enabled) }
        _isLocationSharingEnabled.value = enabled
    }
    
    // ========================================================================
    // MARK: - Channel Memory
    // ========================================================================
    
    /**
     * Remember the last joined festival channel
     */
    var lastJoinedChannel: String?
        get() = prefs.getString(KEY_LAST_JOINED_CHANNEL, null)
        set(value) = prefs.edit { 
            if (value != null) {
                putString(KEY_LAST_JOINED_CHANNEL, value)
            } else {
                remove(KEY_LAST_JOINED_CHANNEL)
            }
        }
    
    // ========================================================================
    // MARK: - Reset
    // ========================================================================
    
    /**
     * Reset all festival settings to defaults
     */
    fun resetToDefaults() {
        prefs.edit { clear() }
        _isFestivalModeEnabled.value = false
        _isLocationSharingEnabled.value = false
    }
}
