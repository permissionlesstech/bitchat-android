package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MeshService` is the sum of small role interfaces, so a client implements the roles it can really
 * do. A role member has no default body, no override of one is an empty or log-only stub, and the
 * composite names exactly the roles. Read as source text from `MeshService.kt`, comments dropped.
 */
class MeshRoleImplementationsTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val meshServiceFile = File(root, "core/mesh/src/main/java/com/bitchat/android/mesh/MeshService.kt")

    private val roleNames = listOf(
        "MeshLifecycle", "MeshPeers", "MeshMessaging", "MeshReceipts",
        "MeshVerification", "MeshPrivateMedia", "MeshDiagnostics", "MeshWipe",
    )

    private val implementers = listOf(
        "app/src/main/java/com/bitchat/android/mesh/UnifiedMeshService.kt",
        "app/src/main/java/com/bitchat/android/wifi-aware/WifiAwareMeshService.kt",
        "wear/src/main/java/com/bitchat/watch/mesh/WearMeshService.kt",
    )

    @Test
    fun noRoleMemberHasADefaultBody() {
        val offenders = interfaceBodies(code(meshServiceFile)).flatMap { (name, body) ->
            membersOf(body).filter { it.hasBody }.map { "$name.${it.name}" }
        }
        val scanned = interfaceBodies(code(meshServiceFile)).sumOf { membersOf(it.second).size }
        assertTrue("Scanned no role members; the scan is broken", scanned > 0)
        assertTrue("Role members with a body: $offenders", offenders.isEmpty())
    }

    @Test
    fun meshServiceIsExactlyTheRoles() {
        val declaration = Regex("""\binterface\s+MeshService\s*:\s*([^{]+)\{""").find(code(meshServiceFile))
        assertTrue("MeshService declaration not found", declaration != null)
        val supertypes = declaration!!.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(roleNames.sorted(), supertypes.sorted())
        val bodies = interfaceBodies(code(meshServiceFile)).toMap()
        assertEquals(roleNames.sorted(), bodies.keys.filter { it != "MeshService" }.sorted())
    }

    @Test
    fun noEmptyOverrideOfARoleMember() {
        val roleMembers = interfaceBodies(code(meshServiceFile))
            .filter { it.first in roleNames }
            .flatMap { membersOf(it.second) }
            .map { it.name }
            .toSet()
        assertTrue("Scanned no role members; the scan is broken", roleMembers.isNotEmpty())
        var overrides = 0
        val offenders = implementers.map { File(root, it) }.filter { it.isFile }.flatMap { file ->
            val text = code(file)
            Regex("""\boverride\s+fun\s+(\w+)\s*\(""").findAll(text)
                .filter { it.groupValues[1] in roleMembers }
                .mapNotNull { match ->
                    overrides++
                    val body = bodyAfterParameters(text, match.range.last)
                    if (isStub(body)) "${file.name}: ${match.groupValues[1]}" else null
                }.toList()
        }
        assertTrue("Scanned no overrides of role members; the scan is broken", overrides > 0)
        assertTrue("Stub overrides of role members: $offenders", offenders.isEmpty())
    }

    @Test
    fun theStubCheckSeesEachFormOfStub() {
        listOf("{ }", "{\n }", "= Unit", "{ Log.d(TAG, \"x\") }", "{ Log.w(TAG, \"a\"); Log.d(TAG, \"b\") }",
            "{ Log.d(TAG, \"x \${f(g(h(1)))}\") }", "{ return null }", "= null", "= true", "{ false }", "= 0", "= \"\"",
            "= emptyList()", "= emptyMap<String, Int>()", "{ return emptySet() }", "= 42")
            .forEach { assertTrue("not seen as a stub: $it", isStub(it)) }
        listOf("{ core.stop() }", "= core.stop()", "{ Log.d(TAG, \"x\"); core.stop() }",
            "= core.activeCount()", "{ return core.has(f(1)) }", "= listOf(1)")
            .forEach { assertTrue("seen as a stub: $it", !isStub(it)) }
    }

    private class Member(val name: String, val hasBody: Boolean)

    /** Each `interface X ... { body }` in [text] as (name, body). */
    private fun interfaceBodies(text: String): List<Pair<String, String>> =
        Regex("""\binterface\s+(\w+)[^{]*\{""").findAll(text).map { match ->
            var depth = 1
            var i = match.range.last + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '{' -> depth++; '}' -> depth-- }
                i++
            }
            match.groupValues[1] to text.substring(match.range.last + 1, i - 1)
        }.toList()

    /** The `fun`, `val` and `var` members of an interface body; a member has a body if `{` or `=` follows its signature. */
    private fun membersOf(body: String): List<Member> {
        val members = mutableListOf<Member>()
        Regex("""\b(fun|val|var)\s+(?:<[^>]*>\s*)?(\w+)""").findAll(body).forEach { match ->
            if (depthAt(body, match.range.first) != 0) return@forEach
            val kind = match.groupValues[1]
            var i = match.range.last + 1
            if (kind == "fun") {
                while (i < body.length && body[i] != '(') i++
                i = skipParens(body, i)
            }
            // Past the parameters: an optional `: Type`, then either nothing, `{` or `=`.
            var j = i
            var angle = 0
            var paren = 0
            var hasBody = false
            while (j < body.length) {
                val c = body[j]
                when {
                    c == '<' -> angle++
                    c == '>' && body[j - 1] != '-' -> angle--
                    c == '(' -> paren++
                    c == ')' -> paren--
                    angle == 0 && paren == 0 && (c == '{' || c == '=') -> { hasBody = true; break }
                    angle == 0 && paren == 0 && c == '\n' -> break
                }
                j++
            }
            members += Member(match.groupValues[2], hasBody)
        }
        return members
    }

    private fun depthAt(text: String, offset: Int): Int {
        var depth = 0
        for (i in 0 until offset) when (text[i]) { '{', '(' -> depth++; '}', ')' -> depth-- }
        return depth
    }

    private fun skipParens(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '(' -> depth++; ')' -> if (--depth == 0) return i + 1 }
        }
        return text.length
    }

    /** The text from the end of the parameter list at [open] (a `(` already consumed) to the end of the member's body. */
    private fun bodyAfterParameters(text: String, openParen: Int): String {
        val afterParams = skipParens(text, openParen)
        var i = afterParams
        while (i < text.length && text[i] != '{' && text[i] != '=' && text[i] != '\n') i++
        // A return type may precede the body: `: Type {` or `: Type =`.
        if (i < text.length && text[i] == '\n') {
            i = afterParams
            while (i < text.length && text[i] != '{' && text[i] != '=') i++
        }
        if (i >= text.length) return ""
        if (text[i] == '=') {
            // An expression body may continue on following lines; it ends at a blank line.
            val blank = Regex("""\n[ \t]*\n""").find(text, i)
            return text.substring(i, blank?.range?.first ?: text.length)
        }
        var depth = 0
        var k = i
        while (k < text.length) {
            when (text[k]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(i, k + 1) }
            k++
        }
        return text.substring(i)
    }

    /**
     * An empty body, `= Unit`, a body of only `Log.*(...)` calls (arguments nested to any depth), or one
     * that only returns a literal or an empty collection: a constant answer that does no work.
     */
    private fun isStub(body: String): Boolean {
        val inner = body.trim().removePrefix("{").removeSuffix("}").removePrefix("=").trim()
        if (inner.isEmpty() || inner == "Unit") return true
        val withoutLogs = removeLogCalls(inner).replace(";", "").trim()
        if (withoutLogs.isEmpty()) return true
        val value = withoutLogs.removePrefix("return").trim()
        return constantValue.matches(value)
    }

    private val constantValue = Regex(
        """null|true|false|-?\d+[LlUuFf]*|-?\d+\.\d+[Ff]?|"[^"]*"|empty(?:List|Map|Set)(?:<[^()]*>)?\(\)"""
    )

    /** [text] without its `Log.x(...)` calls, matching parentheses to any depth. */
    private fun removeLogCalls(text: String): String {
        val out = StringBuilder()
        var i = 0
        val start = Regex("""Log\.\w+\(""")
        while (i < text.length) {
            val match = start.find(text, i)
            if (match == null) { out.append(text, i, text.length); break }
            out.append(text, i, match.range.first)
            i = skipParens(text, match.range.last)
        }
        return out.toString()
    }

    private fun code(file: File): String {
        assertTrue("Missing source: ${file.name}", file.isFile)
        return stripComments(file.readText())
    }

    /** [text] with comments and string text blanked, line breaks kept. */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') { out.append(' '); i++ }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    while (i < end) { out.append(if (text[i] == '\n') '\n' else ' '); i++ }
                }
                text[i] == '"' -> {
                    out.append('"'); i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') { out.append(' '); i++ }
                        out.append(if (text[i] == '\n') '\n' else ' '); i++
                    }
                    out.append('"'); i++
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }
}
