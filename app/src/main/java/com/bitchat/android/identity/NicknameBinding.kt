package com.bitchat.android.identity

import java.text.Normalizer
import java.util.Locale

/**
 * The rule that decides whether a verified seal still applies to the name a
 * peer is currently announcing.
 *
 * A verification binds a FINGERPRINT, which is right — but it is *rendered*
 * beside a self-claimed nickname, and nothing binds those two together. So a
 * key that gets verified once under any name can rename itself onto a nickname
 * the user trusts and keep drawing the seal beside the new one. See
 * [SecureIdentityStateManager.setVerifiedFingerprint].
 *
 * Deliberately free of Android imports so it is reachable from a plain JVM unit
 * test: this is the whole security decision, and it should not be testable only
 * through a view.
 */
object NicknameBinding {

    /**
     * The form two nicknames are compared in to decide whether they are the
     * SAME NAME.
     *
     * NFC, then a locale-independent case fold, then NFC again — case folding
     * can itself emit decomposed sequences (Turkish İ lowercases to i + U+0307),
     * so normalising only once leaves two spellings of one name unequal.
     *
     * `Locale.ROOT` is not optional. `lowercase()` with the default locale makes
     * a Turkish phone fold `I` to `ı` while every other device folds it to `i`,
     * so two users would disagree about whether a peer had renamed.
     *
     * Deliberately NFC and **not** NFKC: a fullwidth `Ｍedic` merely *looks*
     * like `Medic`, so it is a different name and must break the binding.
     * Folding look-alikes is a different question — whether two peers on screen
     * need telling apart — and answering it here would let a vouch for one name
     * quietly cover another.
     */
    fun bindingKey(nickname: String): String =
        nfc(nfc(nickname).lowercase(Locale.ROOT))

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /**
     * Strips ONLY a trailing `#abcd` collision suffix, leaving everything else
     * alone.
     *
     * ASCII hex only. `Char.isDigit()` and friends accept fullwidth digits, so a
     * nickname literally ending in `#ＡＢＣＤ` would otherwise be truncated and
     * could then match a baseline it is not — a seal on a name that was never
     * verified, which is the whole subject of this change.
     */
    fun withoutCollisionSuffix(name: String): String {
        if (name.length < 5) return name
        val tail = name.substring(name.length - 5)
        if (tail[0] != '#') return name
        for (i in 1 until 5) {
            val c = tail[i]
            val isAsciiHex = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!isAsciiHex) return name
        }
        return name.substring(0, name.length - 5)
    }

    /** Two nicknames that are the same name, by [bindingKey]. */
    private fun sameName(a: String, b: String): Boolean = bindingKey(a) == bindingKey(b)

    /**
     * Does a seal earned under [pinned] still apply to a peer ANNOUNCING
     * [announced]?
     *
     * No suffix stripping. What a peer announces is its own string, and a `#`
     * plus four hex is a perfectly legal thing to announce — this app's own
     * `splitSuffix` exists because announced names carry them. Stripping here
     * would let a key pinned as `medic` rename to `medic#cafe` and keep its
     * seal, and would drop the seal of a key honestly verified as `medic#cafe`.
     *
     * Fails **open** when nothing was pinned, or when there is no announced
     * name yet. Peers verified by builds from before this existed have no
     * baseline, and dropping their seals on upgrade would teach people to
     * ignore the signal — which costs more than the narrow case it would catch.
     */
    fun sealAppliesToAnnounced(pinned: String?, announced: String?): Boolean {
        if (pinned.isNullOrEmpty() || announced.isNullOrEmpty()) return true
        return sameName(pinned, announced)
    }

    /**
     * Does a seal earned under [pinned] still apply beside [rendered] — a name
     * as it appears on a row, which the list may have decorated?
     *
     * Two peers claiming one nickname are told apart with a `#abcd` the UI
     * appends, so comparing the decorated string alone would drop the seal of
     * the peer being impersonated at exactly the moment it matters. A raw match
     * is tried first, so a name that genuinely ends in a suffix still matches
     * itself; only then is one suffix removed.
     *
     * This is deliberately a SECOND function rather than a flag. The first
     * version of this patch had one, stripping unconditionally, and it was
     * wrong in both directions at once — which is what happens when one
     * predicate is asked two questions.
     */
    fun sealAppliesToRendered(pinned: String?, rendered: String?): Boolean {
        if (pinned.isNullOrEmpty() || rendered.isNullOrEmpty()) return true
        if (sameName(pinned, rendered)) return true
        val undecorated = withoutCollisionSuffix(rendered)
        if (undecorated.isEmpty()) return true
        return sameName(pinned, undecorated)
    }
}
