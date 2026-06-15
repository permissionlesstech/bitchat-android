package com.bitchat.android.net

import android.content.Context
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TorPreferenceManager {
    private const val KEY_TOR_MODE = "tor_mode"

    private val _modeFlow = MutableStateFlow(TorMode.ON)
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun init(context: Context) {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        val saved = storage.getString(KEY_TOR_MODE, TorMode.ON.name)
        val mode = runCatching { TorMode.valueOf(saved ?: TorMode.ON.name) }.getOrDefault(TorMode.ON)
        _modeFlow.value = mode
    }

    fun set(context: Context, mode: TorMode) {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        storage.putString(KEY_TOR_MODE, mode.name)
        _modeFlow.value = mode
    }

    fun get(context: Context): TorMode {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        val saved = storage.getString(KEY_TOR_MODE, TorMode.ON.name)
        return runCatching { TorMode.valueOf(saved ?: TorMode.ON.name) }.getOrDefault(TorMode.ON)
    }
}
