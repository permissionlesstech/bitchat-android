package com.bitchat.android.onboarding

import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Skip" on the Bluetooth step, for this session only. It survives a rotation
 * and is lost when the Activity finishes or the process dies, as it was when it
 * lived in MainViewModel. Never persisted.
 */
@ActivityRetainedScoped
class BluetoothSkipSession @Inject constructor() {

    private val _skipped = MutableStateFlow(false)
    val skipped: StateFlow<Boolean> = _skipped.asStateFlow()

    fun skip() {
        _skipped.value = true
    }
}
