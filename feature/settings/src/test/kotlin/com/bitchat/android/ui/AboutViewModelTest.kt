package com.bitchat.android.ui

import com.bitchat.android.net.ArtiTorManager.TorStatus
import com.bitchat.android.net.TorMode
import com.bitchat.android.settings.AboutSettingsStore
import com.bitchat.android.settings.BackgroundMeshSetting
import com.bitchat.android.settings.TorStatusSource
import com.bitchat.android.ui.theme.ChatUiMode
import com.bitchat.android.ui.theme.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pins AboutViewModel against fake stores (inventory A-1 reads, AA1-AA15 writes, R1). */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    private lateinit var store: FakeStore
    private lateinit var background: FakeBackground
    private lateinit var tor: FakeTor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakeStore()
        background = FakeBackground()
        tor = FakeTor()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = AboutViewModel(store, background, tor)

    // QA5: PoW is loaded before its flows are first read.
    @Test
    fun init_loadsPowBeforeReadingIt() {
        store.onLoadPow = { store.powEnabled.value = true; store.powDifficulty.value = 20 }

        val state = newViewModel().uiState.value

        assertEquals(1, store.loadPowCalls)
        assertTrue(state.powEnabled)
        assertEquals(20, state.powDifficulty)
    }

    // I2-I13: the first state carries every synchronous read.
    @Test
    fun initialState_seedsFromEachSource() {
        store.theme.value = ThemePreference.Dark
        store.chatUiMode.value = ChatUiMode.Matrix
        store.languageTag = "de"
        store.liveVoice = false
        store.torMode.value = TorMode.OFF
        background.stored = false
        tor.status.value = TorStatus(running = true, bootstrapPercent = 40)

        val state = newViewModel().uiState.value

        assertEquals(ThemePreference.Dark, state.theme)
        assertEquals(ChatUiMode.Matrix, state.chatUiMode)
        assertEquals("de", state.selectedLanguageTag)
        assertFalse(state.liveVoiceEnabled)
        assertFalse(state.backgroundEnabled)
        assertEquals(TorMode.OFF, state.torMode)
        assertEquals(40, state.torStatus.bootstrapPercent)
        assertTrue(state.torAvailable)
    }

    // A1, A5, QA7: version and languages arrive from the background read.
    @Test
    fun versionAndLanguages_areFilledInAfterTheBackgroundRead() = runTest {
        val viewModel = newViewModel()

        val state = withTimeout(5_000) { viewModel.uiState.first { it.versionName.isNotEmpty() } }

        assertEquals("1.2.3", state.versionName)
        assertEquals(listOf("de", "fr"), state.languages.map { it.languageTag })
    }

    // I5, I6, I7, I12: flows are followed.
    @Test
    fun storedFlows_areFollowed() {
        val viewModel = newViewModel()

        store.theme.value = ThemePreference.Light
        store.chatUiMode.value = ChatUiMode.Matrix
        store.powEnabled.value = true
        store.powDifficulty.value = 16
        tor.status.value = TorStatus(running = true, bootstrapPercent = 100)

        val state = viewModel.uiState.value
        assertEquals(ThemePreference.Light, state.theme)
        assertEquals(ChatUiMode.Matrix, state.chatUiMode)
        assertTrue(state.powEnabled)
        assertEquals(16, state.powDifficulty)
        assertEquals(TorStatusLevel.Ready, torStatusLevel(state.torStatus))
    }

    // QA6: background and live voice are read once, not followed.
    @Test
    fun backgroundAndLiveVoice_areReadOnce_currentBehaviour_QA6() {
        val viewModel = newViewModel()

        background.stored = false
        store.liveVoice = false

        assertTrue(viewModel.uiState.value.backgroundEnabled)
        assertTrue(viewModel.uiState.value.liveVoiceEnabled)
    }

    // R1 (was QA1): one Tor mode, from the stored flow, drives both the switch and the card,
    // including a change made elsewhere while About is open.
    @Test
    fun torModeChangedExternally_switchAndCardFollow() {
        val viewModel = newViewModel()
        assertTrue(torSwitchChecked(viewModel.uiState.value.torMode))
        assertTrue(torStatusShown(viewModel.uiState.value.torMode))

        store.torMode.value = TorMode.OFF

        assertFalse(torSwitchChecked(viewModel.uiState.value.torMode))
        assertFalse(torStatusShown(viewModel.uiState.value.torMode))

        viewModel.onAction(AboutAction.TorToggled(enabled = true))

        assertEquals(listOf(TorMode.ON), store.torWrites)
        assertTrue(torSwitchChecked(viewModel.uiState.value.torMode))
        assertTrue(torStatusShown(viewModel.uiState.value.torMode))
    }

    // AA12, QA3
    @Test
    fun torToggle_isIgnoredWhenTorIsUnavailable() {
        tor.available = false
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.TorToggled(enabled = false))

        assertEquals(emptyList<TorMode>(), store.torWrites)
        assertEquals(TorMode.ON, viewModel.uiState.value.torMode)
    }

    // AA3, AA4
    @Test
    fun themeAndChatMode_writeThroughTheStore() {
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.ThemeSelected(ThemePreference.Dark))
        viewModel.onAction(AboutAction.ChatUiModeSelected(ChatUiMode.Matrix))

        assertEquals(ThemePreference.Dark, viewModel.uiState.value.theme)
        assertEquals(ChatUiMode.Matrix, viewModel.uiState.value.chatUiMode)
    }

    // AA7, AA8, A4
    @Test
    fun languageSelection_appliesOnlyAChange() {
        store.languageTag = "de"
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.LanguageSelected("de"))
        assertEquals(emptyList<String>(), store.languageWrites)

        viewModel.onAction(AboutAction.LanguageSelected("fr"))
        viewModel.onAction(AboutAction.LanguageSelected(""))
        viewModel.onAction(AboutAction.LanguageSelected(""))

        assertEquals(listOf("fr", ""), store.languageWrites)
        assertEquals("", viewModel.uiState.value.selectedLanguageTag)
    }

    // AA9, A18
    @Test
    fun backgroundToggle_updatesStateAndThePort() {
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.BackgroundToggled(enabled = false))

        assertFalse(viewModel.uiState.value.backgroundEnabled)
        assertEquals(listOf(false), background.writes)
    }

    // AA10
    @Test
    fun liveVoiceToggle_updatesStateAndTheStore() {
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.LiveVoiceToggled(enabled = false))

        assertFalse(viewModel.uiState.value.liveVoiceEnabled)
        assertEquals(listOf(false), store.liveVoiceWrites)
    }

    // AA11, AA14, A11, QA10
    @Test
    fun powToggleAndEverySliderStep_writeThroughTheStore() {
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.PowToggled(enabled = true))
        viewModel.onAction(AboutAction.PowDifficultyChanged(13))
        viewModel.onAction(AboutAction.PowDifficultyChanged(14))

        assertTrue(viewModel.uiState.value.powEnabled)
        assertEquals(listOf(13, 14), store.difficultyWrites)
        assertEquals(14, viewModel.uiState.value.powDifficulty)
    }

    // AA1, AA15
    @Test
    fun backAndDebug_areEvents() = runTest {
        val viewModel = newViewModel()

        viewModel.onAction(AboutAction.DebugSettingsClicked)
        assertEquals(AboutEvent.NavigateToDebug, viewModel.events.first())

        viewModel.onAction(AboutAction.BackClicked)
        assertEquals(AboutEvent.Close, viewModel.events.first())
    }

    private class FakeStore : AboutSettingsStore {
        override val theme = MutableStateFlow(ThemePreference.System)
        override val chatUiMode = MutableStateFlow(ChatUiMode.Bubbles)
        override val powEnabled = MutableStateFlow(false)
        override val powDifficulty = MutableStateFlow(12)
        override val torMode = MutableStateFlow(TorMode.ON)

        var onLoadPow: () -> Unit = {}
        var loadPowCalls = 0
        var languageTag = ""
        var liveVoice = true
        val languageWrites = mutableListOf<String>()
        val torWrites = mutableListOf<TorMode>()
        val liveVoiceWrites = mutableListOf<Boolean>()
        val difficultyWrites = mutableListOf<Int>()

        override fun loadPow() {
            loadPowCalls++
            onLoadPow()
        }

        override fun currentLanguageTag() = languageTag
        override fun supportedLanguages() = listOf(
            AppLanguage(languageTag = "de", endonym = "Deutsch"),
            AppLanguage(languageTag = "fr", endonym = "Français"),
        )
        override fun isLiveVoiceEnabled() = liveVoice
        override fun versionName() = "1.2.3"

        override fun setTheme(theme: ThemePreference) {
            this.theme.value = theme
        }

        override fun setChatUiMode(mode: ChatUiMode) {
            chatUiMode.value = mode
        }

        override fun setLanguage(tag: String) {
            languageWrites += tag
        }

        override fun setPowEnabled(enabled: Boolean) {
            powEnabled.value = enabled
        }

        override fun setPowDifficulty(difficulty: Int) {
            difficultyWrites += difficulty
            powDifficulty.value = difficulty.coerceIn(0, 32)
        }

        override fun setTorMode(mode: TorMode) {
            torWrites += mode
            torMode.value = mode
        }

        override fun setLiveVoiceEnabled(enabled: Boolean) {
            liveVoiceWrites += enabled
            liveVoice = enabled
        }
    }

    private class FakeBackground : BackgroundMeshSetting {
        var stored = true
        val writes = mutableListOf<Boolean>()
        override fun isEnabled() = stored
        override fun setEnabled(enabled: Boolean) {
            writes += enabled
            stored = enabled
        }
    }

    private class FakeTor : TorStatusSource {
        override val status = MutableStateFlow(TorStatus())
        var available = true
        override fun isTorAvailable() = available
    }
}
