package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageModule
import com.bitchat.android.storage.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Proof of Work preferences for Nostr events
 */
object PoWPreferenceManager {
    
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
    
    private lateinit var storage: StorageRepository
    private var isInitialized = false
    
    /**
     * Initialize the preference manager with application context
     * Should be called once during app startup
     */
    fun init(context: Context) {
        if (isInitialized) return
        
        storage = StorageModule.repository(context, StorageDefinitions.PowPreferences)
        
        // Load current values
        _powEnabled.value = storage.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED)
        _powDifficulty.value = storage.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY)
        
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
        if (::storage.isInitialized) {
            storage.putBoolean(KEY_POW_ENABLED, enabled)
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
        if (::storage.isInitialized) {
            storage.putInt(KEY_POW_DIFFICULTY, clampedDifficulty)
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
