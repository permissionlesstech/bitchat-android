package com.bitchat.android.ui

import com.bitchat.android.feature.settings.R
import com.bitchat.android.net.ArtiTorManager.TorStatus
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.NostrProofOfWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins About's pure decisions (inventory A1-A15). A5 (the locales_config parse) needs
 * Android resources, which this module's JVM tests do not load.
 */
class AboutLogicTest {

    private val languages = listOf(
        AppLanguage(languageTag = "de", endonym = "Deutsch"),
        AppLanguage(languageTag = "fr", endonym = "Français"),
    )

    // A1
    @Test
    fun versionLabel_usesVersionName_emptyWhenNull_fallbackWhenLookupThrows() {
        assertEquals("1.2.3", aboutVersionLabel { "1.2.3" })
        assertEquals("", aboutVersionLabel { null })
        assertEquals("1.0.0", aboutVersionLabel { throw IllegalStateException("no package") })
    }

    // A2
    @Test
    fun selectedLanguageName_isMatchingEndonym_orNullForSystemDefault() {
        assertEquals("Français", selectedLanguageEndonym(languages, "fr"))
        assertNull(selectedLanguageEndonym(languages, ""))
        assertNull(selectedLanguageEndonym(languages, "fr-CA"))
    }

    // A3, QA8
    @Test
    fun menuTicks_systemDefaultIffEmpty_languageIffExactTag_currentBehaviour_QA8() {
        assertTrue(isSystemDefaultSelected(""))
        assertFalse(isSystemDefaultSelected("de"))
        assertTrue(isLanguageSelected(languages[0], "de"))
        assertFalse(isLanguageSelected(languages[0], "fr"))
        // A multi-tag list matches no item, and is not the system default either.
        assertFalse(isSystemDefaultSelected("fr,en"))
        assertFalse(languages.any { isLanguageSelected(it, "fr,en") })
    }

    // A4
    @Test
    fun pickingTheCurrentLanguage_isNoChange() {
        assertNull(languageChangeFor(currentTag = "de", requestedTag = "de"))
        assertNull(languageChangeFor(currentTag = "", requestedTag = ""))
        assertEquals("fr", languageChangeFor(currentTag = "de", requestedTag = "fr"))
        assertEquals("", languageChangeFor(currentTag = "de", requestedTag = ""))
    }

    // A7
    @Test
    fun torSwitch_checkedIffOn_andWritesOnOrOff() {
        assertTrue(torSwitchChecked(TorMode.ON))
        assertFalse(torSwitchChecked(TorMode.OFF))
        assertEquals(TorMode.ON, torModeFor(enabled = true))
        assertEquals(TorMode.OFF, torModeFor(enabled = false))
    }

    // A7, A10
    @Test
    fun torStatusDotAndCard_shownIffOn() {
        assertTrue(torStatusShown(TorMode.ON))
        assertFalse(torStatusShown(TorMode.OFF))
    }

    // QA1: the switch and the status card each seed their own copy of the mode from
    // storage. Flipping the switch updates its copy and storage, never the card's.
    @Test
    fun torSwitchAndCard_readSeparately_currentBehaviour_QA1() {
        var stored = TorMode.ON
        var switchMode = stored
        val cardMode = stored

        switchMode = torModeFor(enabled = false)
        stored = switchMode

        assertEquals(TorMode.OFF, stored)
        assertFalse(torSwitchChecked(switchMode))
        assertTrue(torStatusShown(cardMode))
    }

    // A8
    @Test
    fun torStatusLevel_readyWhenBootstrapped_startingWhileRunning_elseDown() {
        assertEquals(TorStatusLevel.Ready, torStatusLevel(TorStatus(running = true, bootstrapPercent = 100)))
        assertEquals(TorStatusLevel.Starting, torStatusLevel(TorStatus(running = true, bootstrapPercent = 99)))
        assertEquals(TorStatusLevel.Down, torStatusLevel(TorStatus(running = false, bootstrapPercent = 100)))
    }

    // A9
    @Test
    fun torLogLine_hiddenWhenEmpty_truncatedTo120() {
        assertNull(torStatusLogLine(TorStatus(lastLogLine = "")))
        assertEquals("bootstrapping", torStatusLogLine(TorStatus(lastLogLine = "bootstrapping")))
        assertEquals("x".repeat(120), torStatusLogLine(TorStatus(lastLogLine = "x".repeat(200))))
    }

    // A12 (the estimate half; the label is the about_difficulty_value resource)
    @Test
    fun difficultyEstimate_formatsMiningTime() {
        assertEquals("< 1 second", NostrProofOfWork.estimateMiningTime(12))
        assertEquals("10s", NostrProofOfWork.estimateMiningTime(20))
        assertEquals("2m 47s", NostrProofOfWork.estimateMiningTime(24))
    }

    // A13
    @Test
    fun powDescription_bucketsAtTheBoundaries() {
        val expected = mapOf(
            0 to R.string.about_pow_desc_none,
            8 to R.string.about_pow_desc_very_low,
            9 to R.string.about_pow_desc_low,
            12 to R.string.about_pow_desc_low,
            13 to R.string.about_pow_desc_medium,
            16 to R.string.about_pow_desc_medium,
            17 to R.string.about_pow_desc_high,
            20 to R.string.about_pow_desc_high,
            21 to R.string.about_pow_desc_very_high,
            24 to R.string.about_pow_desc_very_high,
            25 to R.string.about_pow_desc_extreme,
            32 to R.string.about_pow_desc_extreme,
        )
        expected.forEach { (difficulty, res) ->
            assertEquals("difficulty $difficulty", res, powDescriptionRes(difficulty))
        }
    }

    // A14
    @Test
    fun sliderPosition_truncatesToDifficulty() {
        assertEquals(12, powDifficultyFromSlider(12.9f))
        assertEquals(0, powDifficultyFromSlider(0f))
        assertEquals(32, powDifficultyFromSlider(32f))
    }

    // A15
    @Test
    fun debugEntry_onSettingsTabOnly_andOnlyWhenAvailable() {
        assertTrue(debugEntryShown(AboutTab.Settings, debugAvailable = true))
        assertFalse(debugEntryShown(AboutTab.Info, debugAvailable = true))
        assertFalse(debugEntryShown(AboutTab.Settings, debugAvailable = false))
    }
}
