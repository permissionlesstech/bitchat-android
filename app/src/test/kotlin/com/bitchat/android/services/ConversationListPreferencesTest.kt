package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ConversationListPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "conversation-list-${UUID.randomUUID()}"
    }

    @Test
    fun `pin mute and draft persist while conversation removal clears all three`() {
        val first = preferences()
        first.togglePinned("CONTACT_ALICE")
        first.toggleMuted("contact_alice")
        first.setDraft("contact_alice", "unfinished reply")

        val restored = preferences()
        assertTrue(restored.isPinned("contact_alice"))
        assertTrue(restored.isMuted("CONTACT_ALICE"))
        assertTrue(restored.draftFor("contact_alice") == "unfinished reply")

        restored.removeConversation("contact_alice")
        val afterDelete = preferences()
        assertFalse(afterDelete.isPinned("contact_alice"))
        assertFalse(afterDelete.isMuted("contact_alice"))
        assertNull(afterDelete.draftFor("contact_alice"))
    }

    @Test
    fun `draft persistence is globally bounded and panic clear is durable`() {
        val preferences = preferences()
        repeat(60) { index ->
            preferences.setDraft("peer-$index", "x".repeat(3_000))
        }

        assertTrue(preferences.drafts.value.size <= 50)
        assertTrue(preferences.drafts.value.values.sumOf(String::length) <= 128_000)
        assertNull(preferences.draftFor("peer-0"))
        assertTrue(preferences.draftFor("peer-59")?.isNotEmpty() == true)

        preferences.togglePinned("peer-59")
        preferences.toggleMuted("peer-59")
        preferences.clearAll()

        val afterPanic = preferences()
        assertTrue(afterPanic.pinned.value.isEmpty())
        assertTrue(afterPanic.muted.value.isEmpty())
        assertTrue(afterPanic.drafts.value.isEmpty())
    }

    private fun preferences(): ConversationListPreferences {
        val sharedPreferences =
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        return ConversationListPreferences(
            stateManager = SecureIdentityStateManager(sharedPreferences, testOnly = true),
            testOnly = true
        )
    }
}
