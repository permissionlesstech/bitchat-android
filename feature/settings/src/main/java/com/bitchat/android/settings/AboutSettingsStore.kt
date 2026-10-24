package com.bitchat.android.settings

import android.content.Context
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.ui.AppLanguage
import com.bitchat.android.ui.LanguagePreferenceManager
import com.bitchat.android.ui.aboutVersionLabel
import com.bitchat.android.ui.theme.ChatUiMode
import com.bitchat.android.ui.theme.ChatUiModeManager
import com.bitchat.android.ui.theme.ThemePreference
import com.bitchat.android.ui.theme.ThemePreferenceManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The preferences About shows and writes, one member per thing the screen uses.
 * The adapter below delegates each one to the preference object that owns it.
 */
interface AboutSettingsStore {

    val theme: StateFlow<ThemePreference>
    val chatUiMode: StateFlow<ChatUiMode>
    val powEnabled: StateFlow<Boolean>
    val powDifficulty: StateFlow<Int>
    val torMode: StateFlow<TorMode>

    /** Loads the stored PoW settings if nothing has yet; a no-op after the first call. */
    fun loadPow()

    /** The app's locale list as language tags; "" is the system default. */
    fun currentLanguageTag(): String

    /** The languages in locales_config. Parses XML: call it off the main thread. */
    fun supportedLanguages(): List<AppLanguage>
    fun isLiveVoiceEnabled(): Boolean

    /** The installed version name. Asks the package manager: call it off the main thread. */
    fun versionName(): String

    fun setTheme(theme: ThemePreference)
    fun setChatUiMode(mode: ChatUiMode)

    /** Applies [tag] ("" for the system default); AppCompat then recreates the Activity. */
    fun setLanguage(tag: String)
    fun setPowEnabled(enabled: Boolean)
    fun setPowDifficulty(difficulty: Int)
    fun setTorMode(mode: TorMode)
    fun setLiveVoiceEnabled(enabled: Boolean)
}

/** Tor's live status, as About's status dot and card read it. */
interface TorStatusSource {
    val status: StateFlow<ArtiTorManager.TorStatus>
    fun isTorAvailable(): Boolean
}

internal class ObjectBackedAboutSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AboutSettingsStore {

    override val theme: StateFlow<ThemePreference> = ThemePreferenceManager.themeFlow
    override val chatUiMode: StateFlow<ChatUiMode> = ChatUiModeManager.modeFlow
    override val powEnabled: StateFlow<Boolean> = PoWPreferenceManager.powEnabled
    override val powDifficulty: StateFlow<Int> = PoWPreferenceManager.powDifficulty
    override val torMode: StateFlow<TorMode> = TorPreferenceManager.modeFlow

    override fun loadPow() = PoWPreferenceManager.init(context)
    override fun currentLanguageTag(): String = LanguagePreferenceManager.currentLanguageTag()
    override fun supportedLanguages(): List<AppLanguage> = LanguagePreferenceManager.supportedLanguages(context)
    override fun isLiveVoiceEnabled(): Boolean = LiveVoicePreferences.isEnabled(context)
    override fun versionName(): String =
        aboutVersionLabel { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    override fun setTheme(theme: ThemePreference) = ThemePreferenceManager.set(context, theme)
    override fun setChatUiMode(mode: ChatUiMode) = ChatUiModeManager.set(context, mode)
    override fun setLanguage(tag: String) = LanguagePreferenceManager.setLanguage(tag)
    override fun setPowEnabled(enabled: Boolean) = PoWPreferenceManager.setPowEnabled(enabled)
    override fun setPowDifficulty(difficulty: Int) = PoWPreferenceManager.setPowDifficulty(difficulty)
    override fun setTorMode(mode: TorMode) = TorPreferenceManager.set(context, mode)
    override fun setLiveVoiceEnabled(enabled: Boolean) = LiveVoicePreferences.setEnabled(context, enabled)
}

internal class ArtiTorStatusSource @Inject constructor() : TorStatusSource {
    private val tor = ArtiTorManager.getInstance()
    override val status: StateFlow<ArtiTorManager.TorStatus> = tor.statusFlow
    override fun isTorAvailable(): Boolean = tor.isTorAvailable()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AboutSettingsModule {
    @Binds
    abstract fun bindAboutSettingsStore(impl: ObjectBackedAboutSettingsStore): AboutSettingsStore

    @Binds
    abstract fun bindTorStatusSource(impl: ArtiTorStatusSource): TorStatusSource
}
