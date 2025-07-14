package com.bitchat.android.wallet.data

import java.math.BigDecimal
import java.util.Date

/**
 * Represents a Cashu token that can be sent or received
 */
data class CashuToken(
    val token: String,
    val amount: BigDecimal,
    val unit: String,
    val mint: String,
    val memo: String? = null
)

/**
 * Represents a Lightning invoice for minting or melting
 */
data class LightningInvoice(
    val paymentRequest: String,
    val amount: BigDecimal,
    val expiry: Date,
    val description: String? = null
)

/**
 * Represents a mint quote for receiving via Lightning
 */
data class MintQuote(
    val id: String,
    val amount: BigDecimal,
    val unit: String,
    val request: String, // Lightning invoice
    val state: MintQuoteState,
    val expiry: Date,
    val paid: Boolean = false
)

/**
 * Represents a melt quote for sending via Lightning
 */
data class MeltQuote(
    val id: String,
    val amount: BigDecimal,
    val feeReserve: BigDecimal,
    val unit: String,
    val request: String, // Lightning invoice to pay
    val state: MeltQuoteState,
    val expiry: Date,
    val paid: Boolean = false
)

enum class MintQuoteState {
    UNPAID,
    PAID,
    ISSUED,
    EXPIRED
}

enum class MeltQuoteState {
    UNPAID,
    PENDING,
    PAID,
    FAILED,
    EXPIRED
}

/**
 * Represents a wallet transaction
 */
data class WalletTransaction(
    val id: String,
    val type: TransactionType,
    val amount: BigDecimal,
    val unit: String,
    val status: TransactionStatus,
    val timestamp: Date,
    val description: String? = null,
    val mint: String? = null,
    val token: String? = null,
    val quote: String? = null,
    val fee: BigDecimal? = null
)

enum class TransactionType {
    CASHU_SEND,
    CASHU_RECEIVE,
    LIGHTNING_SEND,
    LIGHTNING_RECEIVE,
    MINT,
    MELT
}

enum class TransactionStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    EXPIRED
}
