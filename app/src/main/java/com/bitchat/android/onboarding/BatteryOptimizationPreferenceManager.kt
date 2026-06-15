package com.bitchat.android.onboarding

import android.content.Context
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BatteryOptimizationPreferenceManager {
    private const val KEY_BATTERY_SKIP = "battery_optimization_skipped"

    private val _skipFlow = MutableStateFlow(false)
    val skipFlow: StateFlow<Boolean> = _skipFlow

    fun init(context: Context) {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        val skipped = storage.getBoolean(KEY_BATTERY_SKIP, false)
        _skipFlow.value = skipped
    }

    fun setSkipped(context: Context, skipped: Boolean) {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        storage.putBoolean(KEY_BATTERY_SKIP, skipped)
        _skipFlow.value = skipped
    }

    fun isSkipped(context: Context): Boolean {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        return storage.getBoolean(KEY_BATTERY_SKIP, false)
    }
}
