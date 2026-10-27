package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic geohashes only (open ocean at 0°,0°); never a real place. */
class GeohashChannelFormattingTest {

    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun validateGeohash_acceptsOneToTwelveAlphabetCharacters() {
        assertTrue(validateGeohash("s"))
        assertTrue(validateGeohash("zzzzz"))
        assertTrue(validateGeohash("bbbbbbb"))
        assertTrue(validateGeohash("s0000000"))
        assertTrue(validateGeohash("s00000000000"))
    }

    @Test
    fun validateGeohash_rejectsEmptyTooLongUppercaseAndForeignCharacters() {
        assertFalse(validateGeohash(""))
        assertFalse(validateGeohash("s000000000000"))
        assertFalse(validateGeohash("S0000000"))
        assertFalse(validateGeohash("not-a-geohash"))
        // a, i, l and o are not in the geohash alphabet.
        assertFalse(validateGeohash("s0a"))
        assertFalse(validateGeohash("s0i"))
        assertFalse(validateGeohash("s0l"))
        assertFalse(validateGeohash("s0o"))
    }

    @Test
    fun normalizeGeohashInput_lowercasesStripsHashKeepsAlphabetAndTakesTwelve() {
        assertEquals("zzzz", normalizeGeohashInput("#ZZ-zz!"))
        assertEquals("s000", normalizeGeohashInput("s0a0i0"))
        assertEquals("s00000000000", normalizeGeohashInput("s000000000000"))
        assertEquals("s0000000", normalizeGeohashInput(" S0000000 "))
    }

    @Test
    fun rejectedTeleportFieldText_skipsTheAlphabetFilter_currentBehaviour_QL2() {
        assertEquals("not-a-geohash", rejectedTeleportFieldText(" #Not-A-Geohash "))
    }

    @Test
    fun isTeleportEnabled_iffStrippedFieldIsValid() {
        assertTrue(isTeleportEnabled(" #S00000 "))
        assertTrue(isTeleportEnabled("s000000"))
        assertFalse(isTeleportEnabled(""))
        assertFalse(isTeleportEnabled("#"))
        assertFalse(isTeleportEnabled("not-a-geohash"))
        assertFalse(isTeleportEnabled("s000000000000"))
    }

    @Test
    fun mapPickerInitialGeohash_typedThenSelectedLocationThenEmpty() {
        val selected = ChannelID.Location(GeohashChannel(GeohashChannelLevel.BLOCK, "s000000"))

        assertEquals("s00000", mapPickerInitialGeohash(" #S00000 ", selected))
        assertEquals("s000000", mapPickerInitialGeohash("", selected))
        assertEquals("s000000", mapPickerInitialGeohash("  ", selected))
        assertEquals("", mapPickerInitialGeohash("", ChannelID.Mesh))
        assertEquals("", mapPickerInitialGeohash("", null))
    }

    @Test
    fun levelForLength_mapsEachLength() {
        assertEquals(GeohashChannelLevel.REGION, levelForLength(0))
        assertEquals(GeohashChannelLevel.REGION, levelForLength(1))
        assertEquals(GeohashChannelLevel.REGION, levelForLength(2))
        assertEquals(GeohashChannelLevel.PROVINCE, levelForLength(3))
        assertEquals(GeohashChannelLevel.PROVINCE, levelForLength(4))
        assertEquals(GeohashChannelLevel.CITY, levelForLength(5))
        assertEquals(GeohashChannelLevel.NEIGHBORHOOD, levelForLength(6))
        assertEquals(GeohashChannelLevel.BLOCK, levelForLength(7))
        assertEquals(GeohashChannelLevel.BUILDING, levelForLength(8))
        assertEquals(GeohashChannelLevel.BUILDING, levelForLength(9))
        assertEquals(GeohashChannelLevel.BUILDING, levelForLength(12))
    }

    @Test
    fun coverageString_precisionOneTwoToTenAndTwelve() {
        assertEquals("~5000 km", coverageString(1))
        assertEquals("~1250 km", coverageString(2))
        assertEquals("~156 km", coverageString(3))
        assertEquals("~39.1 km", coverageString(4))
        assertEquals("~4.9 km", coverageString(5))
        assertEquals("~1.2 km", coverageString(6))
        assertEquals("~0.2 km", coverageString(7))
        assertEquals("~0.0 km", coverageString(8))
        assertEquals("~0.0 km", coverageString(9))
        assertEquals("~0.0 km", coverageString(10))
        assertEquals("~0.0 km", coverageString(12))
    }

    @Test
    fun coverageString_zeroFallsBackToFiveThousandKm() {
        assertEquals("~5000 km", coverageString(0))
    }

    @Test
    fun formatDistance_wholeFromHundred_oneDecimalBelow_identicalBranches_currentBehaviour_QL9() {
        assertEquals("100", formatDistance(100.0))
        assertEquals("1250", formatDistance(1250.0))
        // The >= 10 and < 10 branches are the same format.
        assertEquals("99.0", formatDistance(99.0))
        assertEquals("10.0", formatDistance(10.0))
        assertEquals("9.9", formatDistance(9.9))
    }

    @Test
    fun coverageString_followsTheDefaultLocale_currentBehaviour_QL9() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("~39,1 km", coverageString(4))
        assertEquals("~1250 km", coverageString(2))
    }

    @Test
    fun meshRangeAndNamePrefix_areHardCodedLiterals_currentBehaviour_QL9() {
        assertEquals("~10–50m", meshRangeString())
        GeohashChannelLevel.entries.forEach { level ->
            assertEquals("~", formattedNamePrefix(level))
        }
    }

    @Test
    fun isChannelSelected_needsSameLevelAndExactCaseGeohash() {
        val channel = GeohashChannel(GeohashChannelLevel.CITY, "s00000")

        assertTrue(isChannelSelected(channel, ChannelID.Location(channel)))
        assertFalse(
            isChannelSelected(
                channel,
                ChannelID.Location(GeohashChannel(GeohashChannelLevel.CITY, "S00000"))
            )
        )
        assertFalse(
            isChannelSelected(
                channel,
                ChannelID.Location(GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "s00000"))
            )
        )
        assertFalse(isChannelSelected(channel, ChannelID.Mesh))
        assertFalse(isChannelSelected(channel, null))
    }
}
