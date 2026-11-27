package com.bitchat.android.panic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Clears the clipboard, where a message, an address or a key may have been copied. */
class ClipboardPanicCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
) : PanicCleanup {
    override val name: String = "clipboard"

    override fun run(): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        return true
    }
}
