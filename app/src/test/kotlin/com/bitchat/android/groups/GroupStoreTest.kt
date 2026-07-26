package com.bitchat.android.groups

import com.google.gson.JsonParser
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

        val legacyGroups = JsonParser.parseString(file.readText())
            .asJsonObject
            .getAsJsonArray("groups")
        file.writeText(legacyGroups.toString())
        val reloadedFromLegacyMetadata = GroupStore(keys, file, testOnly = true)
        assertEquals(listOf(group), reloadedFromLegacyMetadata.groups.value)

        keys.remove("groupKey-${group.groupID.joinToString("") { "%02x".format(it) }}")
        val withoutKey = GroupStore(keys, file, testOnly = true)
        assertTrue(withoutKey.groups.value.isEmpty())
    }

    @Test
    fun `metadata failure rolls back epoch key and in-memory state`() {
        val keys = MemoryGroupKeys()
        val metadata = MemoryGroupMetadata()
        val store = GroupStore(keys, metadata, testOnly = true)
        val group = store.createGroup("ops", creator())!!
        val originalKey = store.key(group.groupID)!!
        val originalMetadata = metadata.contents
        val newMember = GroupMember("22".repeat(32), ByteArray(32) { 0x33 }, "alice")

        metadata.failWrites = true
        val rotation = store.rotateKey(group.groupID, group.members + newMember)

        assertNull(rotation)
        assertEquals(group, store.group(group.groupID))
        assertArrayEquals(originalKey, store.key(group.groupID))
        assertEquals(originalMetadata, metadata.contents)

        metadata.failWrites = false
        val reloaded = GroupStore(keys, metadata, testOnly = true)
        assertEquals(group, reloaded.group(group.groupID))
        assertArrayEquals(originalKey, reloaded.key(group.groupID))
    }

    @Test
    fun `voluntary departure survives restart until a newer invite is accepted`() {
        val keys = MemoryGroupKeys()
        val file = File(temporaryFolder.root, "groups.json")
        val store = GroupStore(keys, file, testOnly = true)
        val group = store.createGroup("ops", creator())!!

        assertTrue(store.departGroup(group.groupID, group.epoch))
        assertNull(store.group(group.groupID))
        assertNull(store.key(group.groupID))
        assertEquals(group.epoch, store.departureEpoch(group.groupID))

        val reloaded = GroupStore(keys, file, testOnly = true)
        assertNull(reloaded.group(group.groupID))
        assertEquals(group.epoch, reloaded.departureEpoch(group.groupID))

        val reinvited = group.copy(epoch = group.epoch + 1)
        val newKey = ByteArray(32) { 0x55 }
        assertTrue(reloaded.acceptInvite(reinvited, newKey))
        assertEquals(reinvited, reloaded.group(group.groupID))
        assertArrayEquals(newKey, reloaded.key(group.groupID))
        assertNull(reloaded.departureEpoch(group.groupID))
    }

    @Test
    fun `android-style store remains inert until background initialization`() {
        val keys = MemoryGroupKeys()
        val file = File(temporaryFolder.root, "groups.json")
        val seeded = GroupStore(keys, file, testOnly = true)
        val group = seeded.createGroup("ops", creator())!!
        val deferred = GroupStore(
            keys,
            file,
            testOnly = true,
            autoInitialize = false
        )

        assertFalse(deferred.isReady)
        assertTrue(deferred.groups.value.isEmpty())
        assertNull(deferred.createGroup("too early", creator()))

        assertTrue(deferred.initialize())
        assertTrue(deferred.isReady)
        assertEquals(group, deferred.group(group.groupID))
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

private class MemoryGroupMetadata : GroupMetadataStorage {
    var contents: String? = null
    var failWrites = false

    override fun read(): String? = contents

    override fun write(contents: String): Boolean {
        if (failWrites) return false
        this.contents = contents
        return true
    }

    override fun delete(): Boolean {
        contents = null
        return true
    }
}
