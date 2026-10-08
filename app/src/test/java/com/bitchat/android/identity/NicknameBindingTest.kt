package com.bitchat.android.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rename attack and the ways a fix for it can go wrong.
 *
 * Every case here was reachable before the binding existed, or is a way an
 * over-eager binding would have broken something legitimate. The logic lives in
 * a pure-Kotlin object precisely so it can be tested here rather than only
 * through a Composable.
 */
class NicknameBindingTest {

    // ---- the attack -------------------------------------------------------

    @Test
    fun `renaming onto a trusted nickname breaks the seal`() {
        // Eve is verified while announcing "ravi", then announces "medic". Every
        // device that trusts the real medic would otherwise show a second
        // trusted-looking medic.
        assertFalse(NicknameBinding.sealAppliesToAnnounced("ravi", "medic"))
    }

    @Test
    fun `the seal stands while the name is unchanged`() {
        assertTrue(NicknameBinding.sealAppliesToAnnounced("ravi", "ravi"))
    }

    // ---- what counts as the same name -------------------------------------

    @Test
    fun `recasing your own nickname is not a rename`() {
        // A rename is meant to break the binding; a recase is not. Without the
        // case fold, changing "Ravi" to "ravi" silently dropped the seal.
        assertTrue(NicknameBinding.sealAppliesToAnnounced("Ravi", "ravi"))
        assertTrue(NicknameBinding.sealAppliesToAnnounced("ravi", "RAVI"))
    }

    @Test
    fun `a combining accent is the same name as a precomposed one`() {
        val precomposed = "José"        // José
        val decomposed = "José"        // Jose + combining acute
        assertEquals(NicknameBinding.bindingKey(precomposed), NicknameBinding.bindingKey(decomposed))
        assertTrue(NicknameBinding.sealAppliesToAnnounced(precomposed, decomposed))
    }

    @Test
    fun `case folding is normalised again afterwards`() {
        // Turkish dotted capital I lowercases to i + U+0307, which is a
        // DECOMPOSED sequence. Normalising only before the fold leaves two
        // spellings of one name unequal.
        val dotted = "İstanbul"
        assertEquals(NicknameBinding.bindingKey(dotted), NicknameBinding.bindingKey(dotted.lowercase()))
    }

    @Test
    fun `a look-alike does break the binding`() {
        // The other half of the case fold. A fullwidth Ｍ merely LOOKS like M,
        // so it is a different name and must break the binding — folding
        // look-alikes is a different question, and answering it here would let
        // a verification for one name quietly cover another.
        assertFalse(NicknameBinding.sealAppliesToAnnounced("Medic", "Ｍedic"))
        // ...and so does a Cyrillic М.
        assertFalse(NicknameBinding.sealAppliesToAnnounced("Medic", "Меdic"))
    }

    // ---- the collision suffix ---------------------------------------------

    @Test
    fun `a hash suffix on the rendered name is ignored`() {
        // Two peers claiming one nickname render as "medic#a1b2" and
        // "medic#c3d4". Comparing the decorated string would drop the seal of
        // the peer being impersonated, at exactly the moment it matters most.
        assertTrue(NicknameBinding.sealAppliesToAnnounced("medic", "medic"))
        assertEquals("medic", NicknameBinding.withoutCollisionSuffix("medic#a1b2"))
    }

    @Test
    fun `only a trailing ASCII hex suffix is stripped`() {
        // `Char.isDigit()` accepts fullwidth digits, so a nickname literally
        // ending in "#ＡＢＣＤ" would be truncated and could then match a
        // baseline it is not — a seal on a name that was never verified.
        assertEquals("medic#ＡＢＣＤ",
                     NicknameBinding.withoutCollisionSuffix("medic#ＡＢＣＤ"))
        assertEquals("medic#zzzz", NicknameBinding.withoutCollisionSuffix("medic#zzzz"))
        assertEquals("medic#abc", NicknameBinding.withoutCollisionSuffix("medic#abc"))
        // Only ONE suffix comes off — the name keeps whatever else it had.
        assertEquals("x#abcd", NicknameBinding.withoutCollisionSuffix("x#abcd#abcd"))
    }

    @Test
    fun `an at sign in a nickname survives`() {
        // Nothing in the app forbids "@" in a nickname, and the mention parser's
        // splitSuffix strips every "@" in the string — right for parsing a
        // mention, wrong for comparing a name, since "ravi@hq" would then
        // compare unequal to itself.
        assertTrue(NicknameBinding.sealAppliesToAnnounced("ravi@hq", "ravi@hq"))
        assertTrue(NicknameBinding.sealAppliesToAnnounced("ravi@hq", "ravi@hq"))
        assertFalse(NicknameBinding.sealAppliesToAnnounced("ravi@hq", "ravi"))
    }

    // ---- failing open ------------------------------------------------------

    @Test
    fun `a missing baseline never suppresses`() {
        // Peers verified by builds from before this existed have no baseline.
        // Dropping their seals on upgrade would teach people to ignore the
        // signal, which costs more than the narrow case it would catch.
        assertTrue(NicknameBinding.sealAppliesToAnnounced(null, "medic"))
        assertTrue(NicknameBinding.sealAppliesToAnnounced("", "medic"))
    }

    @Test
    fun `an empty current name never suppresses`() {
        // A row with no name to show yet is not evidence of a rename.
        assertTrue(NicknameBinding.sealAppliesToAnnounced("medic", null))
        assertTrue(NicknameBinding.sealAppliesToAnnounced("medic", ""))
        // A peer that announces "#a1b2" has renamed, and says so — on either
        // path, since a mesh row shows what was announced.
        assertFalse(NicknameBinding.sealAppliesToAnnounced("medic", "#a1b2"))
        assertFalse(NicknameBinding.sealAppliesToRendered("medic", "#a1b2"))
    }

    // ---- announced vs rendered: the split that was missing -----------------

    @Test
    fun `an announced suffix is not a UI decoration`() {
        // The first version of this patch stripped a trailing #abcd from the
        // LIVE announced name, which is wrong in both directions at once. A
        // peer announces whatever string it likes, and "#" plus four hex is a
        // legal thing to announce — this app's own splitSuffix exists because
        // announced names carry them.
        //
        // Stripping let the attack straight through...
        assertFalse(NicknameBinding.sealAppliesToAnnounced("medic", "medic#cafe"))
        // ...and dropped the seal of a key honestly verified under a name that
        // simply ends that way.
        assertTrue(NicknameBinding.sealAppliesToAnnounced("medic#cafe", "medic#cafe"))
    }

    @Test
    fun `a rendered mesh row carries no decoration to remove`() {
        // Nothing decorates a mesh nickname on this platform: splitSuffix only
        // separates a suffix the peer announced, and the one synthesised #abcd
        // is for Nostr people, which isPeerVerified refuses outright. So a row
        // name IS the announced name, and stripping would be a fail-open.
        assertFalse(NicknameBinding.sealAppliesToRendered("medic", "medic#cafe"))
        assertTrue(NicknameBinding.sealAppliesToRendered("medic#cafe", "medic#cafe"))
        assertFalse(NicknameBinding.sealAppliesToRendered("medic", "zebra#a1b2"))
    }

    // ---- the key itself ----------------------------------------------------

    @Test
    fun `the binding key is idempotent`() {
        for (name in listOf("Medic", "ravi@hq", "José", "İstanbul", "Ｍedic", "")) {
            val once = NicknameBinding.bindingKey(name)
            assertEquals("bindingKey is not stable for \"$name\"", once, NicknameBinding.bindingKey(once))
        }
    }

    @Test
    fun `unrelated names do not match`() {
        assertFalse(NicknameBinding.sealAppliesToAnnounced("medic", "zebra"))
        assertFalse(NicknameBinding.sealAppliesToAnnounced("medic", "medic2"))
        assertFalse(NicknameBinding.sealAppliesToAnnounced("medic", "medi"))
    }
}
