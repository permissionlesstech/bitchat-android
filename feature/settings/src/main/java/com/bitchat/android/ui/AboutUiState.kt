package com.bitchat.android.ui

import androidx.annotation.StringRes
import com.bitchat.android.feature.settings.R
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode

// The pure decisions About makes (inventory A1-A15), kept out of the composables so
// they can be pinned without a device.

/** A1: the hero's version label; "1.0.0" if the package lookup throws, "" if it has none. */
internal fun aboutVersionLabel(readVersionName: () -> String?): String =
    try {
        readVersionName() ?: ""
    } catch (e: Exception) {
        "1.0.0"
    }

/** A2: the selected language's endonym, or null when the system default should be shown. */
internal fun selectedLanguageEndonym(languages: List<AppLanguage>, selectedTag: String): String? =
    languages.firstOrNull { it.languageTag == selectedTag }?.endonym

/** A3: "System default" is ticked iff no language tag is set. */
internal fun isSystemDefaultSelected(selectedTag: String): Boolean = selectedTag.isEmpty()

/** A3: a language is ticked iff its tag is the selected one. */
internal fun isLanguageSelected(language: AppLanguage, selectedTag: String): Boolean =
    selectedTag == language.languageTag

/** A4: the tag to apply when [requestedTag] is picked, or null when it is already the current one. */
internal fun languageChangeFor(currentTag: String, requestedTag: String): String? =
    requestedTag.takeIf { it != currentTag }

/** A7: the Tor switch is on iff Tor mode is ON. */
internal fun torSwitchChecked(mode: TorMode): Boolean = mode == TorMode.ON

/** A7: the mode the Tor switch writes when flipped to [enabled]. */
internal fun torModeFor(enabled: Boolean): TorMode = if (enabled) TorMode.ON else TorMode.OFF

/** A7 and A10: the status dot and the status card show only while Tor mode is ON. */
internal fun torStatusShown(mode: TorMode): Boolean = mode == TorMode.ON

/** A8: the colour bucket of the Tor status dot. */
internal enum class TorStatusLevel { Ready, Starting, Down }

/** A8: ready once bootstrapped, starting while running, otherwise down. */
internal fun torStatusLevel(status: ArtiTorManager.TorStatus): TorStatusLevel = when {
    status.running && status.bootstrapPercent >= 100 -> TorStatusLevel.Ready
    status.running -> TorStatusLevel.Starting
    else -> TorStatusLevel.Down
}

/** A9: the log line under the status, at most 120 characters, or null when there is none. */
internal fun torStatusLogLine(status: ArtiTorManager.TorStatus): String? =
    status.lastLogLine.takeIf { it.isNotEmpty() }?.take(120)

/** A13: the description under the PoW slider for [difficulty] bits. */
@StringRes
internal fun powDescriptionRes(difficulty: Int): Int = when {
    difficulty == 0 -> R.string.about_pow_desc_none
    difficulty <= 8 -> R.string.about_pow_desc_very_low
    difficulty <= 12 -> R.string.about_pow_desc_low
    difficulty <= 16 -> R.string.about_pow_desc_medium
    difficulty <= 20 -> R.string.about_pow_desc_high
    difficulty <= 24 -> R.string.about_pow_desc_very_high
    else -> R.string.about_pow_desc_extreme
}

/** A14: the slider's float position as the difficulty it writes (truncated, clamped later). */
internal fun powDifficultyFromSlider(value: Float): Int = value.toInt()

/** A15: Debug settings is offered on the Settings tab only, and only when it can be opened. */
internal fun debugEntryShown(selectedTab: AboutTab, debugAvailable: Boolean): Boolean =
    selectedTab == AboutTab.Settings && debugAvailable
