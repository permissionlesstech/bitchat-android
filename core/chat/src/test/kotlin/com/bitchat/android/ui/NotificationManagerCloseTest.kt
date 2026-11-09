package com.bitchat.android.ui

import android.app.Application
import android.app.Activity
import android.content.BroadcastReceiver
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A closed manager holds no preview text and takes no more. All text here is synthetic. */
@RunWith(RobolectricTestRunner::class)
class NotificationManagerCloseTest {

    private val system = mock<NotificationManagerCompat>()
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        // A library's resources do not reach a JVM test, so every string is stood in for.
        val context: Application = Mockito.spy(RuntimeEnvironment.getApplication())
        doReturn("synthetic").whenever(context).getString(any<Int>())
        doReturn("synthetic").whenever(context).getString(any<Int>(), anyVararg<Any>())
        doAnswer { context }.whenever(context).applicationContext
        val directory = mock<ContactDirectory> {
            on { canonicalConversationId(any()) } doAnswer { it.getArgument(0) }
        }
        val preferences = mock<ConversationListPreferences> { on { isMuted(any()) } doReturn false }
        manager = NotificationManager(
            context,
            system,
            NotificationTargets(activity = Activity::class.java, actionReceiver = BroadcastReceiver::class.java),
            directory,
            preferences,
        )
        manager.setAppBackgroundState(true)
    }

    private fun showOneOfEach() {
        // Building the system notification can need what a JVM test lacks; the previews are what count.
        runCatching { manager.showPrivateMessageNotification("synthetic-peer", "anon0000", "synthetic text") }
        runCatching { manager.showGeohashNotification("s0000", "anon0001", "synthetic text") }
        runCatching { manager.showMeshMentionNotification("anon0002", "synthetic text") }
    }

    @Test
    fun `closing drops every pending preview`() {
        showOneOfEach()
        assertEquals(3, manager.pendingPreviewCount)

        manager.close()

        assertEquals(0, manager.pendingPreviewCount)
    }

    @Test
    fun `a closed manager takes no more previews and posts nothing`() {
        manager.close()
        Mockito.clearInvocations(system)

        showOneOfEach()

        assertEquals(0, manager.pendingPreviewCount)
        verify(system, never()).notify(any<Int>(), anyOrNull())
    }
}
