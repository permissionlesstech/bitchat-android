package com.bitchat.android.wallet.ui

/**
 * Utility functions for wallet UI components
 */
object WalletUtils {
    /**
     * Format satoshis for display
     */
    fun formatSats(sats: Long): String {
        return when {
            sats >= 100_000_000 -> String.format("%.2f BTC", sats / 100_000_000.0)
            sats >= 1000 -> String.format("%,d sats", sats)
            else -> "$sats sats"
        }
    }
} 