package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Proof of Work preferences for Nostr events
 */
object PoWPreferenceManager {
    
    private const val PREFS_NAME = "pow_preferences"
    private const val KEY_POW_ENABLED = "pow_enabled"
    private const val KEY_POW_DIFFICULTY = "pow_difficulty"
    
    // Default values
    private const val DEFAULT_POW_ENABLED = false
    private const val DEFAULT_POW_DIFFICULTY = 12 // Reasonable default for geohash spam prevention
    
    // State flows for reactive UI
    private val _powEnabled = MutableStateFlow(DEFAULT_POW_ENABLED)
    val powEnabled: StateFlow<Boolean> = _powEnabled.asStateFlow()
    
    private val _powDifficulty = MutableStateFlow(DEFAULT_POW_DIFFICULTY)
    val powDifficulty: StateFlow<Int> = _powDifficulty.asStateFlow()
    
    // Mining state for animated indicators
    private val _isMining = MutableStateFlow(false)
    val isMining: StateFlow<Boolean> = _isMining.asStateFlow()
    
    private lateinit var sharedPrefs: SharedPreferences
    private var isInitialized = false
    
    /**
     * Initialize the preference manager with application context
     * Should be called once during app startup
     */
    fun init(context: Context) {
        if (isInitialized) return
        
        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load current values
        _powEnabled.value = sharedPrefs.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED)
        _powDifficulty.value = sharedPrefs.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY)
        
        isInitialized = true
    }
    
    /**
     * Get current PoW enabled state
     */
    fun isPowEnabled(): Boolean {
        return _powEnabled.value
    }
    
    /**
     * Set PoW enabled state
     */
    fun setPowEnabled(enabled: Boolean) {
        _powEnabled.value = enabled
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_POW_ENABLED, enabled).apply()
        }
    }
    
    /**
     * Get current PoW difficulty setting
     */
    fun getPowDifficulty(): Int {
        return _powDifficulty.value
    }
    
    /**
     * Set PoW difficulty (clamped between 0 and 32)
     */
    fun setPowDifficulty(difficulty: Int) {
        val clampedDifficulty = difficulty.coerceIn(0, 32)
        _powDifficulty.value = clampedDifficulty
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putInt(KEY_POW_DIFFICULTY, clampedDifficulty).apply()
        }
    }
    
    /**
     * Get current settings as a data class
     */
    data class PoWSettings(
        val enabled: Boolean,
        val difficulty: Int
    )
    
    /**
     * Get current settings
     */
    fun getCurrentSettings(): PoWSettings {
        return PoWSettings(
            enabled = _powEnabled.value,
            difficulty = _powDifficulty.value
        )
    }
    
    /**
     * Reset to default settings
     */
    fun resetToDefaults() {
        setPowEnabled(DEFAULT_POW_ENABLED)
        setPowDifficulty(DEFAULT_POW_DIFFICULTY)
    }
    
    /**
     * Get difficulty levels with descriptions for UI
     */
    fun getDifficultyLevels(): List<Pair<Int, String>> {
        return listOf(
            0 to "Disabled (no PoW)",
            8 to "Very Low (instant)",
            12 to "Low (~0.1s)",
            16 to "Medium (~2s)",
            20 to "High (~30s)",
            24 to "Very High (~8m)",
            28 to "Extreme (~2h)",
            32 to "Maximum (~8h)"
        )
    }
    
    /**
     * Get current mining state
     */
    fun isMining(): Boolean {
        return _isMining.value
    }
    
    /**
     * Start mining state - triggers animated indicators
     */
    fun startMining() {
        _isMining.value = true
    }
    
    /**
     * Stop mining state - stops animated indicators
     */
    fun stopMining() {
        _isMining.value = false
    }
}
