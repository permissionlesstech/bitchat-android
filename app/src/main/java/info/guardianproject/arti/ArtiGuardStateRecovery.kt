package info.guardianproject.arti

import java.io.File

/**
 * Arti refuses to start when `state/guards.json` is not valid JSON, then every
 * retry reads the same file. Moving that file aside lets the next init rebuild
 * guard state without touching the rest of the app's data.
 */
object ArtiGuardStateRecovery {
    fun isCorruptPersistentGuardState(logs: List<String>): Boolean {
        val text = logs.joinToString("\n").lowercase()
        if (text.isBlank()) return false
        val mentionsGuardsFile = text.contains("guards.json")
        val jsonLoadFailure = text.contains("json error") ||
            text.contains("expected value") ||
            text.contains("corrupted data in persistent state")
        if (mentionsGuardsFile && jsonLoadFailure) return true
        return text.contains("problem accessing persistent guard state")
    }

    /**
     * Rename `guards.json` so Arti can create a fresh one. Other files in the
     * state directory stay put. Returns the quarantined file, or null when
     * there was nothing to move.
     */
    fun quarantineGuardsFile(dataDir: File, nowMillis: Long = System.currentTimeMillis()): File? {
        // The JNI bridge configures dataDir/state as the root. Arti's persistent-state
        // manager creates its own state directory within that root.
        val stateDir = dataDir.resolve("state").resolve("state")
        val guards = File(stateDir, "guards.json")
        if (!guards.isFile) return null
        var dest = File(stateDir, "guards.json.corrupt-$nowMillis")
        var suffix = 0
        while (dest.exists()) {
            suffix++
            dest = File(stateDir, "guards.json.corrupt-$nowMillis-$suffix")
        }
        if (!guards.renameTo(dest)) {
            guards.copyTo(dest, overwrite = false)
            if (!guards.delete()) {
                dest.delete()
                return null
            }
        }
        return dest
    }
}
