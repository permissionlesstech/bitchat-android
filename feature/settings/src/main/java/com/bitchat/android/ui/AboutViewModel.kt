package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.settings.AboutSettingsStore
import com.bitchat.android.settings.BackgroundMeshSetting
import com.bitchat.android.settings.TorStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * About's state and the writes behind its controls.
 *
 * Theme, chat mode, PoW and the Tor mode follow their stored flows, so the Tor switch and
 * the status card read the same value. Background mode and live voice are read once, when
 * About opens. The version and the language list are read off the main thread.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val store: AboutSettingsStore,
    private val backgroundMesh: BackgroundMeshSetting,
    private val tor: TorStatusSource,
) : ViewModel() {

    private val _uiState: MutableStateFlow<AboutUiState>
    val uiState: StateFlow<AboutUiState>

    private val _events = Channel<AboutEvent>(Channel.BUFFERED)
    val events: Flow<AboutEvent> = _events.receiveAsFlow()

    init {
        // Normally done at startup already; idempotent, and it must precede the first read.
        store.loadPow()
        _uiState = MutableStateFlow(
            AboutUiState(
                theme = store.theme.value,
                chatUiMode = store.chatUiMode.value,
                languages = emptyList(),
                selectedLanguageTag = store.currentLanguageTag(),
                powEnabled = store.powEnabled.value,
                powDifficulty = store.powDifficulty.value,
                backgroundEnabled = backgroundMesh.isEnabled(),
                liveVoiceEnabled = store.isLiveVoiceEnabled(),
                torMode = store.torMode.value,
                torStatus = tor.status.value,
                torAvailable = tor.isTorAvailable(),
                versionName = "",
            )
        )
        uiState = _uiState.asStateFlow()

        follow(store.theme) { copy(theme = it) }
        follow(store.chatUiMode) { copy(chatUiMode = it) }
        follow(store.powEnabled) { copy(powEnabled = it) }
        follow(store.powDifficulty) { copy(powDifficulty = it) }
        follow(store.torMode) { copy(torMode = it) }
        follow(tor.status) { copy(torStatus = it) }

        viewModelScope.launch {
            val (versionName, languages) = withContext(Dispatchers.IO) {
                store.versionName() to store.supportedLanguages()
            }
            _uiState.update { it.copy(versionName = versionName, languages = languages) }
        }
    }

    fun onAction(action: AboutAction) {
        when (action) {
            AboutAction.BackClicked -> _events.trySend(AboutEvent.Close)
            AboutAction.DebugSettingsClicked -> _events.trySend(AboutEvent.NavigateToDebug)
            is AboutAction.ThemeSelected -> store.setTheme(action.theme)
            is AboutAction.ChatUiModeSelected -> store.setChatUiMode(action.mode)
            is AboutAction.LanguageSelected ->
                languageChangeFor(_uiState.value.selectedLanguageTag, action.languageTag)?.let { tag ->
                    _uiState.update { it.copy(selectedLanguageTag = tag) }
                    store.setLanguage(tag)
                }
            is AboutAction.BackgroundToggled -> {
                _uiState.update { it.copy(backgroundEnabled = action.enabled) }
                backgroundMesh.setEnabled(action.enabled)
            }
            is AboutAction.LiveVoiceToggled -> {
                _uiState.update { it.copy(liveVoiceEnabled = action.enabled) }
                store.setLiveVoiceEnabled(action.enabled)
            }
            is AboutAction.PowToggled -> store.setPowEnabled(action.enabled)
            is AboutAction.PowDifficultyChanged -> store.setPowDifficulty(action.difficulty)
            is AboutAction.TorToggled ->
                if (_uiState.value.torAvailable) store.setTorMode(torModeFor(action.enabled))
        }
    }

    private fun <T> follow(flow: StateFlow<T>, apply: AboutUiState.(T) -> AboutUiState) {
        viewModelScope.launch { flow.collect { value -> _uiState.update { it.apply(value) } } }
    }
}
