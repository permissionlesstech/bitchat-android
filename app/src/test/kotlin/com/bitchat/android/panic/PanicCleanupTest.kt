package com.bitchat.android.panic

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.debug.DebugMessage
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.debug.DebugScanResult
import com.bitchat.android.ui.debug.DebugSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowShortcutManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanicCleanupTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notification cleanup cancels every notification and removes the conversation shortcuts`() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("synthetic", "synthetic", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, "synthetic").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        NotificationManagerCompat.from(context).apply {
            notify(1, notification)
            notify(2, notification)
        }
        ShortcutManagerCompat.pushDynamicShortcut(
            context,
            ShortcutInfoCompat.Builder(context, "synthetic-shortcut")
                .setShortLabel("synthetic")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_dialog_info))
                .build(),
        )
        assertEquals(2, shadowOf(manager).allNotifications.size)
        assertEquals(1, ShortcutManagerCompat.getDynamicShortcuts(context).size)

        assertTrue(NotificationsPanicCleanup(context).run())

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }

    @Config(shadows = [CachedShortcutsShadow::class])
    @Test
    fun `the notification cleanup removes a cached long-lived shortcut that is no longer dynamic`() {
        // Robolectric keeps no cached shortcuts, so the shadow holds one as the system would after
        // the shortcut was pushed out of the dynamic set.
        CachedShortcutsShadow.cached = listOf(
            ShortcutInfo.Builder(context, "synthetic-cached")
                .setShortLabel("synthetic")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build(),
        )
        try {
            assertTrue(NotificationsPanicCleanup(context).run())

            assertEquals(listOf("synthetic-cached"), CachedShortcutsShadow.removedLongLived)
        } finally {
            CachedShortcutsShadow.cached = emptyList()
            CachedShortcutsShadow.removedLongLived.clear()
        }
    }

    @Test
    fun `the clipboard cleanup leaves no clip`() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "synthetic secret"))

        assertTrue(ClipboardPanicCleanup(context).run())

        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 && it.getItemAt(0).text?.isNotEmpty() == true })
    }

    @Config(sdk = [27])
    @Test
    fun `below API 28 the clipboard cleanup sets an empty clip`() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "synthetic secret"))

        assertTrue(ClipboardPanicCleanup(context).run())

        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 && it.getItemAt(0).text?.isNotEmpty() == true })
    }

    @Test
    fun `the debug buffers cleanup empties the buffers`() {
        val debug = DebugSettingsManager(DebugPreferenceManager())
        debug.addDebugMessage(DebugMessage.SystemMessage("synthetic"))
        debug.addScanResult(DebugScanResult("synthetic", "00:00:00:00:00:01", -40, "peer-a"))
        debug.setDebugSheetVisible(true)
        debug.logOutgoing("MESSAGE", "peer-a", "synthetic", "00:00:00:00:00:01")
        assertTrue(debug.perPeerOutgoingTotal.value.isNotEmpty())

        assertTrue(DebugBuffersPanicCleanup(debug).run())

        assertTrue(debug.perPeerOutgoingTotal.value.isEmpty())
        assertTrue(debug.perPeerOutgoingLastMinute.value.isEmpty())
        assertTrue(debug.perDeviceOutgoingTotal.value.isEmpty())

        assertTrue(debug.debugMessages.value.isEmpty())
        assertTrue(debug.scanResults.value.isEmpty())
    }

    @Test
    fun `each cleanup is named`() {
        val names = listOf(
            NotificationsPanicCleanup(context).name,
            ClipboardPanicCleanup(context).name,
            DebugBuffersPanicCleanup(DebugSettingsManager(DebugPreferenceManager())).name,
        )
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.isNotBlank() })
    }
}

/** Adds the cached shortcuts Robolectric's shortcut manager does not keep. */
@Implements(ShortcutManager::class)
class CachedShortcutsShadow : ShadowShortcutManager() {
    companion object {
        var cached: List<ShortcutInfo> = emptyList()
        val removedLongLived = mutableListOf<String>()
    }

    @Implementation
    override fun getShortcuts(matchFlags: Int): List<ShortcutInfo> =
        super.getShortcuts(matchFlags) + if (matchFlags and ShortcutManager.FLAG_MATCH_CACHED != 0) cached else emptyList()

    @Implementation
    override fun removeLongLivedShortcuts(shortcutIds: List<String>) {
        removedLongLived += shortcutIds
        super.removeLongLivedShortcuts(shortcutIds)
    }
}
