package com.bitchat.android.panic

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

/** The Keystore entries the wipe must delete. A fake stands in for it where there is no Keystore. */
interface KeyAliases {
    fun aliases(): List<String>
    fun delete(alias: String)
}

object AndroidKeyAliases : KeyAliases {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun aliases(): List<String> = store().aliases().toList()

    override fun delete(alias: String) = store().deleteEntry(alias)
}

/**
 * @property leftovers paths that survive the wipe, relative to a sandbox root (never absolute,
 * so they are safe to count or log); a kept preferences file with an unlisted key is listed too.
 */
data class WipeResult(val leftovers: List<String>, val aliasesLeft: Int) {
    val confirmed: Boolean get() = leftovers.isEmpty() && aliasesLeft == 0
}

/**
 * Wipes the app's sandbox by location: everything under the data directory (except `lib` and
 * `code_cache`), the external files and external cache directories, and every Keystore alias,
 * except what [KeepList] names. Needs no Hilt and knows no identity, so the startup re-check can
 * run it from `Application.onCreate()`.
 */
class SandboxWipe(
    private val context: Context,
    private val keepList: KeepList = KeepList,
    private val keyStore: KeyAliases = AndroidKeyAliases,
) {
    private val dataDir: File = context.dataDir
    private val keptPrefs = keepList.prefs.associateBy { it.file }
    private val keptPaths = keepList.paths.map { it.relative }

    /**
     * Preferences files whose commit or delete reported failure, where the memory map then proves
     * nothing, and the preferences or database directory when it could not be listed.
     */
    private val prefsFailures = linkedSetOf<String>()

    fun run(): WipeResult {
        prefsFailures.clear()
        wipePreferences()
        wipeDatabases()
        roots().forEach { (_, root, kept) -> deleteChildren(root, "", kept) }
        deleteKeystoreAliases()
        return WipeResult(
            leftovers = findLeftovers(),
            aliasesLeft = try {
                keyStore.aliases().size
            } catch (_: Exception) {
                // An unreadable Keystore is not proof it is empty.
                1
            },
        )
    }

    /** A sandbox root: its label in leftovers, the directory, and the kept paths relative to it. */
    private fun roots(): List<Triple<String, File, List<String>>> = buildList {
        add(Triple("", dataDir, keptPaths))
        context.getExternalFilesDir(null)?.let { add(Triple("external_files/", it, emptyList())) }
        context.externalCacheDir?.let { add(Triple("external_cache/", it, emptyList())) }
    }

    private fun wipePreferences() {
        val dir = File(dataDir, "shared_prefs")
        val listed = dir.list { _, n -> n.endsWith(".xml") }
        // A directory that cannot be listed is not proof it is empty.
        if (listed == null && dir.exists()) prefsFailures += "shared_prefs"
        val names = listed.orEmpty().map { it.removeSuffix(".xml") }
        for (name in names) {
            val kept = keptPrefs[name]
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (kept != null) {
                val drop = prefs.all.keys.filterNot(kept::keeps)
                if (drop.isNotEmpty()) {
                    val committed = prefs.edit().apply { drop.forEach { remove(it) } }.commit()
                    if (!committed) prefsFailures += "shared_prefs/$name.xml"
                }
            } else {
                val committed = prefs.edit().clear().commit()
                val deleted = context.deleteSharedPreferences(name)
                if (!committed || !deleted) prefsFailures += "shared_prefs/$name.xml"
            }
        }
    }

    private fun wipeDatabases() {
        val dir = File(dataDir, "databases")
        val listed = dir.list()
        if (listed == null && dir.exists()) prefsFailures += "databases"
        for (name in listed.orEmpty()) {
            if (isKept("databases/$name", keptPaths)) continue
            if (JOURNAL_SUFFIXES.any { name.endsWith(it) }) continue
            context.deleteDatabase(name)
        }
    }

    private fun deleteKeystoreAliases() {
        val aliases = try {
            keyStore.aliases()
        } catch (_: Exception) {
            return
        }
        for (alias in aliases) {
            try {
                keyStore.delete(alias)
            } catch (_: Exception) {
                // Counted as left by the final aliases() check.
            }
        }
    }

    private fun deleteChildren(dir: File, relative: String, kept: List<String>) {
        val children = dir.listFiles().orEmpty()
        for (child in children) {
            val rel = relative + child.name
            if (relative.isEmpty() && child.name in SKIPPED_TOP_LEVEL) continue
            if (isKept(rel, kept)) continue
            if (hasKeptDescendant(rel, kept) && child.isRealDirectory()) {
                deleteChildren(child, "$rel/", kept)
            } else {
                deleteTree(child)
            }
        }
    }

    private fun deleteTree(file: File) {
        if (file.isRealDirectory()) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }

    private fun findLeftovers(): List<String> {
        val leftovers = mutableListOf<String>()
        leftovers += prefsFailures
        for ((label, root, kept) in roots()) collect(root, "", label, kept, leftovers)
        for ((name, kept) in keptPrefs) {
            if (!File(dataDir, "shared_prefs/$name.xml").exists()) continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val rel = "shared_prefs/$name.xml"
            if (rel !in leftovers && prefs.all.keys.any { !kept.keeps(it) }) leftovers += rel
        }
        return leftovers
    }

    private fun collect(
        dir: File,
        relative: String,
        label: String,
        kept: List<String>,
        out: MutableList<String>,
    ) {
        val children = dir.listFiles()
        if (children == null) {
            // A directory that cannot be listed is not proof it is empty.
            if (dir.exists()) out += label + relative.removeSuffix("/").ifEmpty { "." }
            return
        }
        for (child in children) {
            val rel = relative + child.name
            if (relative.isEmpty() && child.name in SKIPPED_TOP_LEVEL) continue
            if (isKept(rel, kept)) continue
            if (hasKeptDescendant(rel, kept) && child.isRealDirectory()) {
                collect(child, "$rel/", label, kept, out)
            } else {
                out += label + rel
            }
        }
    }

    private fun File.isRealDirectory(): Boolean =
        isDirectory && !Files.isSymbolicLink(toPath())

    /** [rel] is a kept path, lies under a kept directory, or matches a trailing-`*` entry. */
    private fun isKept(rel: String, kept: List<String>): Boolean = kept.any { k ->
        if (k.endsWith("*")) {
            // Same directory, same name prefix.
            rel.startsWith(k.removeSuffix("*")) && rel.substringBeforeLast('/', "") == k.substringBeforeLast('/', "")
        } else {
            rel == k || rel.startsWith("$k/")
        }
    }

    /** [rel] is a directory on the way to a kept path, so it is walked rather than deleted. */
    private fun hasKeptDescendant(rel: String, kept: List<String>): Boolean =
        kept.any { it.startsWith("$rel/") }

    private companion object {
        val SKIPPED_TOP_LEVEL = setOf("lib", "code_cache")
        val JOURNAL_SUFFIXES = listOf("-journal", "-wal", "-shm")
    }
}
