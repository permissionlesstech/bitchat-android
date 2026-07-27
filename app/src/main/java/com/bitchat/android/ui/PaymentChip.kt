package com.bitchat.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.bitchat.android.cashu.CashuTokenDecoder
import java.net.URLEncoder

/**
 * Payment chip for a Cashu ecash bearer token detected in a chat message.
 *
 * Renders "🥜 500 sat · mint.example.com" with the memo as a caption when the
 * token decodes, degrading to a generic "pay via cashu" chip otherwise.
 * Tap redeems: a wallet app registered for `cashu:` URLs is tried first, with
 * a browser fallback to redeem.cashu.me. The app never contacts a mint itself.
 *
 * Port of iOS `PaymentChipView` (bitchat PR #1376).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CashuPaymentChip(token: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colorScheme = MaterialTheme.colorScheme
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // Decoded once per token; tokens are size-capped so this is cheap.
    val info = remember(token) { CashuTokenDecoder.decode(token) }
    var menuExpanded by remember { mutableStateOf(false) }

    val genericLabel = "pay via cashu"
    val primaryLabel = remember(info) {
        if (info == null) return@remember genericLabel
        val parts = mutableListOf<String>()
        info.displayAmount?.let { parts.add(it) }
        info.mintHost?.let { parts.add(it) }
        if (parts.isEmpty()) genericLabel else parts.joinToString(" · ")
    }

    val fgColor = colorScheme.primary
    val bgColor = colorScheme.secondary.copy(alpha = if (isDark) 0.18f else 0.12f)
    val borderColor = fgColor.copy(alpha = 0.25f)

    fun webRedeemUrl(): String =
        "https://redeem.cashu.me/?token=" + URLEncoder.encode(token, "UTF-8")

    fun openUrl(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun redeem() {
        // Try a wallet registered for cashu: URLs first, then the web page.
        // The token only reaches the site the user's browser loads.
        if (!openUrl("cashu:$token")) openUrl(webRedeemUrl())
    }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(bgColor, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = { redeem() },
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text = "🥜", fontSize = 12.sp)
            Column(modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    text = primaryLabel,
                    color = fgColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                info?.memo?.let { memo ->
                    Text(
                        text = memo,
                        color = fgColor.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy token") },
                onClick = {
                    clipboard.setText(AnnotatedString(token))
                    menuExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Redeem in wallet") },
                onClick = {
                    openUrl("cashu:$token")
                    menuExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Redeem on web") },
                onClick = {
                    openUrl(webRedeemUrl())
                    menuExpanded = false
                }
            )
        }
    }
}
