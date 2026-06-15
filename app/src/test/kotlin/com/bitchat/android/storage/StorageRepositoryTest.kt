package com.bitchat.android.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRepositoryTest {

    @After
    fun tearDown() {
        PanicClearRegistry.resetForTesting()
    }

    @Test
    fun `runs migrations once in version order`() {
        val store = FakeKeyValueStore()
        val repository = StorageRepository(testDefinition(), store)
        val applied = mutableListOf<Int>()

        val migrations = listOf(
            StorageMigration(toVersion = 2) {
                applied += 2
                it.putString("v2", "done")
            },
            StorageMigration(toVersion = 1) {
                applied += 1
                it.putString("v1", "done")
            }
        )

        repository.runMigrations(migrations)
        repository.runMigrations(migrations)

        assertEquals(listOf(1, 2), applied)
        assertEquals("done", repository.getString("v1"))
        assertEquals("done", repository.getString("v2"))
        assertEquals(2, repository.getInt("__storage_version", 0))
    }

    @Test
    fun `panic clear removes only owned keys when definition scopes keys`() {
        val definition = testDefinition(
            clearMode = StorageClearMode.REMOVE_OWNED_KEYS,
            ownedKeys = setOf("owned_a", "owned_b")
        )
        val repository = StorageRepository(definition, FakeKeyValueStore())

        repository.putString("owned_a", "a")
        repository.putString("owned_b", "b")
        repository.putString("unowned", "keep")

        repository.clearForPanic()

        assertNull(repository.getString("owned_a"))
        assertNull(repository.getString("owned_b"))
        assertEquals("keep", repository.getString("unowned"))
    }

    @Test
    fun `panic clear clears full scope for normal panic stores`() {
        val repository = StorageRepository(testDefinition(), FakeKeyValueStore())
        repository.putString("one", "1")
        repository.putBoolean("two", true)

        repository.clearForPanic()

        assertFalse(repository.contains("one"))
        assertFalse(repository.contains("two"))
    }

    @Test
    fun `panic clear does nothing for stores kept on panic`() {
        val repository = StorageRepository(
            testDefinition(panicClearPolicy = PanicClearPolicy.KEEP_ON_PANIC),
            FakeKeyValueStore()
        )
        repository.putString("setting", "keep")

        repository.clearForPanic()

        assertEquals("keep", repository.getString("setting"))
    }

    @Test
    fun `registry reports successes and failures without stopping clear`() {
        var successCalled = false
        PanicClearRegistry.register(testDefinition(id = "success", owner = "SuccessStore")) {
            successCalled = true
        }
        PanicClearRegistry.register(testDefinition(id = "failure", owner = "FailureStore")) {
            error("boom")
        }

        val results = PanicClearRegistry.clearAll()

        assertTrue(successCalled)
        assertEquals(2, results.size)
        assertTrue(results.first { it.id == "success" }.success)
        val failure = results.first { it.id == "failure" }
        assertFalse(failure.success)
        assertEquals("boom", failure.errorMessage)
    }

    @Test
    fun `register if absent preserves richer manager clear handler`() {
        val definition = testDefinition(id = "store")
        val calls = mutableListOf<String>()

        PanicClearRegistry.register(definition) { calls += "manager" }
        PanicClearRegistry.registerIfAbsent(definition) { calls += "default" }

        PanicClearRegistry.clearAll()

        assertEquals(listOf("manager"), calls)
    }

    private fun testDefinition(
        id: String = "test_store",
        owner: String = "TestStore",
        panicClearPolicy: PanicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC,
        clearMode: StorageClearMode = StorageClearMode.CLEAR_SCOPE,
        ownedKeys: Set<String> = emptySet()
    ): StorageDefinition {
        return StorageDefinition(
            id = id,
            owner = owner,
            prefsName = "${id}_prefs",
            security = StorageSecurity.NORMAL,
            panicClearPolicy = panicClearPolicy,
            clearMode = clearMode,
            ownedKeys = ownedKeys
        )
    }

    private class FakeKeyValueStore : KeyValueStore {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String, defaultValue: String?): String? {
            return values[key] as? String ?: defaultValue
        }

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return values[key] as? Boolean ?: defaultValue
        }

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun getInt(key: String, defaultValue: Int): Int {
            return values[key] as? Int ?: defaultValue
        }

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun getLong(key: String, defaultValue: Long): Long {
            return values[key] as? Long ?: defaultValue
        }

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }

        override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? Set<String>)?.toSet() ?: defaultValue
        }

        override fun putStringSet(key: String, value: Set<String>) {
            values[key] = value.toSet()
        }

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun clear() {
            values.clear()
        }
    }
}
