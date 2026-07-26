package com.bitchat.android.groups

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GroupStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `create read rotate and remove`() {
        val keys = MemoryGroupKeys()
        val store = GroupStore(keys, testOnly = true)
        val group = store.createGroup("hike", creator())!!
        val originalKey = store.key(group.groupID)!!

        assertEquals(1, group.epoch)
        assertEquals(group, store.group(group.groupID))
        assertArrayEquals(originalKey, store.key(group.groupID))

        val newMember = GroupMember("22".repeat(32), ByteArray(32) { 0x33 }, "alice")
        val (rotated, newKey) = store.rotateKey(group.groupID, listOf(creator(), newMember))!!

        assertEquals(2, rotated.epoch)
        assertEquals(2, rotated.members.size)
        assertFalse(originalKey.contentEquals(newKey))
        assertArrayEquals(newKey, store.key(group.groupID))

        store.removeGroup(group.groupID)
        assertNull(store.group(group.groupID))
        assertNull(store.key(group.groupID))
    }

    @Test
    fun `persistence reloads metadata only when key survives`() {
        val keys = MemoryGroupKeys()
        val file = File(temporaryFolder.root, "groups.json")
        val first = GroupStore(keys, file, testOnly = true)
        val group = first.createGroup("ops", creator())!!

        val reloaded = GroupStore(keys, file, testOnly = true)
        assertEquals(listOf(group), reloaded.groups.value)

        keys.remove("groupKey-${group.groupID.joinToString("") { "%02x".format(it) }}")
        val withoutKey = GroupStore(keys, file, testOnly = true)
        assertTrue(withoutKey.groups.value.isEmpty())
    }

    @Test
    fun `creator and roster cap are enforced`() {
        val store = GroupStore(MemoryGroupKeys(), testOnly = true)
        val missingCreator = BitchatGroup(
            groupID = ByteArray(16),
            name = "bad",
            epoch = 1,
            members = listOf(GroupMember("22".repeat(32), ByteArray(32), "member")),
            creatorFingerprint = "11".repeat(32)
        )
        assertFalse(store.upsert(missingCreator, ByteArray(32)))

        val tooMany = BitchatGroup(
            groupID = ByteArray(16),
            name = "full",
            epoch = 1,
            members = List(17) { index ->
                GroupMember("%02x".format(index).repeat(32), ByteArray(32) { index.toByte() }, "m$index")
            },
            creatorFingerprint = "00".repeat(32)
        )
        assertFalse(store.upsert(tooMany, ByteArray(32)))
    }

    @Test
    fun `panic wipe clears keys metadata and memory`() {
        val keys = MemoryGroupKeys()
        val file = File(temporaryFolder.root, "groups.json")
        val store = GroupStore(keys, file, testOnly = true)
        val group = store.createGroup("gone", creator())!!
        assertTrue(file.exists())
        assertNotNull(store.key(group.groupID))

        store.wipe()

        assertTrue(store.groups.value.isEmpty())
        assertNull(store.key(group.groupID))
        assertFalse(file.exists())
    }

    private fun creator() =
        GroupMember("11".repeat(32), ByteArray(32) { 0x44 }, "creator")
}

private class MemoryGroupKeys : GroupKeyStorage {
    private val values = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(key: String, value: ByteArray): Boolean {
        values[key] = value.copyOf()
        return true
    }

    override fun remove(key: String): Boolean = values.remove(key) != null
}
