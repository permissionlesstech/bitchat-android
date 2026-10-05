package com.bitchat.android.geohash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A geohash that names no cell has to be distinguishable from one that names a
 * cell at (0, 0): the origin is a real place in the Gulf of Guinea, so anything
 * that picks relays or geocodes a name from a decoded centre would otherwise
 * act on a location the user never chose.
 */
class GeohashValidityTest {

    @Test
    fun `base32 strings are valid regardless of case`() {
        assertTrue(Geohash.isValid("u4pruydqqvj"))
        assertTrue(Geohash.isValid("U4PRUYDQQVJ"))
        assertTrue(Geohash.isValid("9"))
    }

    @Test
    fun `empty and malformed strings are invalid`() {
        assertFalse(Geohash.isValid(""))
        // a, i, l and o are not geohash digits
        assertFalse(Geohash.isValid("hello"))
        assertFalse(Geohash.isValid("u4pr!"))
        assertFalse(Geohash.isValid("café"))
    }

    @Test
    fun `nullable decoders reject what the plain ones map onto the origin`() {
        assertEquals(0.0 to 0.0, Geohash.decodeToCenter(""))
        assertEquals(0.0 to 0.0, Geohash.decodeToCenter("hello"))

        assertNull(Geohash.decodeToCenterOrNull(""))
        assertNull(Geohash.decodeToCenterOrNull("hello"))
        assertNull(Geohash.decodeToBoundsOrNull(""))
        assertNull(Geohash.decodeToBoundsOrNull("hello"))
    }

    @Test
    fun `nullable decoders agree with the plain ones on real cells`() {
        val geohash = "u4pruydqqvj"
        assertEquals(Geohash.decodeToCenter(geohash), Geohash.decodeToCenterOrNull(geohash))
        assertEquals(Geohash.decodeToBounds(geohash), Geohash.decodeToBoundsOrNull(geohash))
        assertNotNull(Geohash.decodeToCenterOrNull("s000"))
    }

    @Test
    fun `a cell at the origin still decodes`() {
        // "s000..." is the cell containing (0, 0) — a valid geohash, not a failure.
        val center = Geohash.decodeToCenterOrNull("s0000")
        assertNotNull(center)
        assertTrue(center!!.first in -1.0..1.0)
        assertTrue(center.second in -1.0..1.0)
    }
}
