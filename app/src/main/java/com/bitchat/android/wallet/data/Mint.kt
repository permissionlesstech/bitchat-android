package com.bitchat.android.wallet.data

import java.util.Date

/**
 * Represents contact information for a mint
 */
data class ContactInfo(
    val method: String, // e.g., "email", "twitter", "nostr"
    val info: String    // e.g., "contact@example.com", "@username", "npub..."
)

/**
 * Represents a Cashu mint information
 */
data class MintInfo(
    val url: String,
    val name: String,
    val description: String? = null,
    val descriptionLong: String? = null,
    val contact: List<ContactInfo>? = null,
    val version: String,
    val nuts: Map<String, Any>? = null, // NUT specifications supported by the mint
    val motd: String? = null,
    val icon: String? = null,
    val time: Date? = null
)

/**
 * Represents a mint in the user's wallet
 */
data class Mint(
    val url: String,
    var nickname: String,
    val info: MintInfo?,
    val keysets: List<MintKeyset>,
    val active: Boolean = true,
    val dateAdded: Date = Date(),
    val lastSync: Date? = null
)

/**
 * Represents a mint's keyset
 */
data class MintKeyset(
    val id: String,
    val unit: String,
    val keys: Map<String, String>, // Amount to public key mapping
    val active: Boolean,
    val validFrom: Date? = null,
    val validTo: Date? = null
)

/**
 * Represents the wallet's balance for a specific mint and unit
 */
data class WalletBalance(
    val mint: String,
    val unit: String,
    val amount: Long,
    val lastUpdated: Date = Date()
)
