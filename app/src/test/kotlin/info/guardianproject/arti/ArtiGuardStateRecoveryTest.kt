package info.guardianproject.arti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtiGuardStateRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val corruptInitLog = """
        ERROR: Failed to initialize Arti: tor: corrupted data in persistent state: Error setting up the guard manager
        Caused by:
          0: Error setting up the guard manager
          1: Problem accessing persistent guard state
          2: JSON error while loading persistent data on state/guards.json
          3: expected value at line 1 column 1
    """.trimIndent()

    @Test
    fun `the reported guards json failure is recoverable`() {
        assertTrue(ArtiGuardStateRecovery.isCorruptPersistentGuardState(listOf(corruptInitLog)))
    }

    @Test
    fun `a usable guard log is not treated as corruption`() {
        assertFalse(
            ArtiGuardStateRecovery.isCorruptPersistentGuardState(
                listOf("We have found that guard [scrubbed] is usable.")
            )
        )
    }

    @Test
    fun `a generic init failure does not quarantine guard state`() {
        assertFalse(
            ArtiGuardStateRecovery.isCorruptPersistentGuardState(
                listOf("ERROR: Failed to initialize Arti: error code -3")
            )
        )
        assertFalse(
            ArtiGuardStateRecovery.isCorruptPersistentGuardState(
                listOf("ERROR: Failed to create Tokio runtime")
            )
        )
    }

    @Test
    fun `quarantine renames only guards json`() {
        val dataDir = temporaryFolder.newFolder("arti")
        val configuredRoot = dataDir.resolve("state")
        val stateDir = configuredRoot.resolve("state").apply { mkdirs() }
        val guards = stateDir.resolve("guards.json").apply { writeText("") }
        val other = stateDir.resolve("keys").apply { writeText("keep-me") }
        val outerGuards = configuredRoot.resolve("guards.json").apply { writeText("outer-file") }

        val quarantined = ArtiGuardStateRecovery.quarantineGuardsFile(dataDir, nowMillis = 42L)

        assertEquals("guards.json.corrupt-42", quarantined?.name)
        assertTrue(quarantined!!.isFile)
        assertEquals("", quarantined.readText())
        assertFalse(guards.exists())
        assertEquals("keep-me", other.readText())
        assertEquals("outer-file", outerGuards.readText())
    }

    @Test
    fun `missing guards json is left alone`() {
        val dataDir = temporaryFolder.newFolder("empty-lookup")
        val outerGuards = dataDir.resolve("state").apply { mkdir() }
            .resolve("guards.json").apply { writeText("outer-file") }

        assertNull(ArtiGuardStateRecovery.quarantineGuardsFile(dataDir))
        assertEquals("outer-file", outerGuards.readText())
    }
}
