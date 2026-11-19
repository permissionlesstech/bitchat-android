package com.bitchat.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPeerDataInLogsTest {

    private val uiSourceDirectory = File("src/main/java/com/bitchat/android/ui")
    private val sensitiveNames = Regex("peerid|favorite|nickname", RegexOption.IGNORE_CASE)
    private val interpolation = Regex("""\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    @Test
    fun peerListSources_logNoPeerIdsOrFavourites() {
        val files = uiSourceDirectory.listFiles { file ->
            file.name.endsWith(".kt") && listOf("MeshPeerList", "PeerList", "GeohashPeople", "MeshPeople", "ConversationRow")
                .any { file.name.startsWith(it) }
        }?.sortedBy { it.name }.orEmpty()

        assertTrue("No peer-list sources found under ${uiSourceDirectory.path}", files.isNotEmpty())

        val violations = files.flatMap { file -> violationsIn(file) }
        assertTrue(
            "Peer-list Log calls interpolate peer data:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun violationsIn(file: File): List<String> {
        val text = file.readText()
        return Regex("""\bLog\.""").findAll(text).mapNotNull { match ->
            val end = closingParenIndex(text, match.range.last) ?: return@mapNotNull null
            val call = text.substring(match.range.first, end + 1)
            val leaked = interpolation.findAll(call).any { m ->
                val expression = m.groups[1]?.value ?: m.groups[2]?.value.orEmpty()
                sensitiveNames.containsMatchIn(expression)
            }
            if (leaked) {
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.name}:$line"
            } else {
                null
            }
        }.toList()
    }

    /**
     * Returns the index of the parenthesis that closes the first '(' found after [from],
     * skipping over string literals. Returns null if the call has no argument list.
     */
    private fun closingParenIndex(text: String, from: Int): Int? {
        val open = text.indexOf('(', from)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}
