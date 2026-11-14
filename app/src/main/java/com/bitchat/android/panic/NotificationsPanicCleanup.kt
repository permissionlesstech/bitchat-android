package com.bitchat.android.panic

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Removes what the app left in the system's notification shade and launcher: every notification,
 * and the dynamic and long-lived conversation shortcuts that carry contact names. The session's
 * in-memory pending lists die with the session.
 */
class NotificationsPanicCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
) : PanicCleanup {
    override val name: String = "notifications"

    override fun run(): Boolean {
        NotificationManagerCompat.from(context).cancelAll()
        val shortcutIds = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        if (shortcutIds.isNotEmpty()) {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIds)
        }
        return true
    }
}
