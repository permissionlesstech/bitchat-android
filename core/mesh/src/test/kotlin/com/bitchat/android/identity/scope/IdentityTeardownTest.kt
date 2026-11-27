package com.bitchat.android.identity.scope

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityTeardownTest {

    @Test
    fun `cleanups run newest first`() {
        val teardown = IdentityTeardown()
        val ran = mutableListOf<String>()
        teardown.register("first") { ran += "first" }
        teardown.register("second") { ran += "second" }
        teardown.register("third") { ran += "third" }

        assertEquals(emptyList<String>(), teardown.runAll())

        assertEquals(listOf("third", "second", "first"), ran)
    }

    @Test
    fun `a cleanup that throws does not stop the others`() {
        val teardown = IdentityTeardown()
        val ran = mutableListOf<String>()
        teardown.register("first") { ran += "first" }
        teardown.register("broken") { error("synthetic failure") }
        teardown.register("third") { ran += "third" }

        val failed = teardown.runAll()

        assertEquals(listOf("third", "first"), ran)
        assertEquals(listOf("broken"), failed)
    }

    @Test
    fun `runAll runs once`() {
        val teardown = IdentityTeardown()
        var runs = 0
        teardown.register("only") { runs++ }

        teardown.runAll()
        assertEquals(emptyList<String>(), teardown.runAll())

        assertEquals(1, runs)
    }
}
