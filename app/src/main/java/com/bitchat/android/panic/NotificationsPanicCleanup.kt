package com.bitchat.android.panic

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Removes what the app left in the system's notification shade and launcher: every notification,
 * and the dynamic and cached long-lived conversation shortcuts that carry contact names. The session's
 * in-memory pending lists die with the session.
 */
class NotificationsPanicCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
) : PanicCleanup {
    override val name: String = "notifications"

    override fun run(): Boolean {
        NotificationManagerCompat.from(context).cancelAll()
        // A long-lived shortcut pushed out of the dynamic set stays cached, contact name and all.
        val shortcutIds = ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
        ).map { it.id }.distinct()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        if (shortcutIds.isNotEmpty()) {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIds)
        }
        return true
    }
}
