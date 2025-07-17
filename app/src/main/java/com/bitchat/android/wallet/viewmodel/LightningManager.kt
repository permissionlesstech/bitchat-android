package com.bitchat.android.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.bitchat.android.wallet.data.*
import com.bitchat.android.wallet.repository.WalletRepository
import com.bitchat.android.wallet.service.CashuService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages all lightning-related operations for the wallet
 */
class LightningManager(
    private val repository: WalletRepository,
    private val cashuService: CashuService,
    private val coroutineScope: CoroutineScope,
    private val uiStateManager: UIStateManager
) {
    companion object {
        private const val TAG = "LightningManager"
    }
    
    // Observable data
    private val _currentMintQuote = MutableLiveData<MintQuote?>(null)
    val currentMintQuote: androidx.lifecycle.LiveData<MintQuote?> = _currentMintQuote
    
    private val _currentMeltQuote = MutableLiveData<MeltQuote?>(null)
    val currentMeltQuote: androidx.lifecycle.LiveData<MeltQuote?> = _currentMeltQuote
    
    private val _pendingMintQuotes = MutableLiveData<List<MintQuote>>(emptyList())
    val pendingMintQuotes: androidx.lifecycle.LiveData<List<MintQuote>> = _pendingMintQuotes
    
    private val _pendingMeltQuotes = MutableLiveData<List<MeltQuote>>(emptyList())
    val pendingMeltQuotes: androidx.lifecycle.LiveData<List<MeltQuote>> = _pendingMeltQuotes
    
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: androidx.lifecycle.LiveData<String?> = _errorMessage
    
    /**
     * Create Lightning mint quote (for receiving)
     */
    fun createMintQuote(amount: Long, description: String? = null) {
        coroutineScope.launch {
            try {
                uiStateManager.setLoading(true)
                
                // Create the mint quote first
                val mintQuoteResult = cashuService.createMintQuote(amount, description)
                mintQuoteResult.onSuccess { quote ->
                    _currentMintQuote.value = quote
                    
                    // Save quote for tracking
                    val saveResult = repository.saveMintQuote(quote)
                    saveResult.onSuccess {
                        // Now load pending quotes directly in current coroutine
                        loadPendingQuotesInternal()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to save mint quote", error)
                        _errorMessage.value = "Failed to save invoice: ${error.message}"
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Failed to create invoice: ${error.message}"
                }
            } finally {
                uiStateManager.setLoading(false)
            }
        }
    }
    
    /**
     * Create Lightning melt quote (for sending)
     */
    fun createMeltQuote(invoice: String, onQuoteCreated: () -> Unit = {}) {
        coroutineScope.launch {
            try {
                uiStateManager.setLoading(true)
                
                // Create the melt quote first
                val meltQuoteResult = cashuService.createMeltQuote(invoice)
                meltQuoteResult.onSuccess { quote ->
                    _currentMeltQuote.value = quote
                    
                    // Save quote for tracking
                    val saveResult = repository.saveMeltQuote(quote)
                    saveResult.onSuccess {
                        // Load pending quotes directly in current coroutine
                        loadPendingQuotesInternal()
                        onQuoteCreated()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to save melt quote", error)
                        _errorMessage.value = "Failed to save quote: ${error.message}"
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Failed to process invoice: ${error.message}"
                }
            } finally {
                uiStateManager.setLoading(false)
            }
        }
    }
    
    /**
     * Pay Lightning invoice (melt)
     */
    fun payLightningInvoice(
        quoteId: String,
        onTransactionSaved: () -> Unit,
        onBalanceRefresh: () -> Unit,
        onPaymentComplete: () -> Unit
    ) {
        coroutineScope.launch {
            try {
                uiStateManager.setLoading(true)
                cashuService.payInvoice(quoteId).onSuccess { success ->
                    if (success) {
                        // Update quote status and add transaction
                        val quote = _currentMeltQuote.value
                        if (quote != null) {
                            val updatedQuote = quote.copy(paid = true, state = MeltQuoteState.PAID)
                            repository.saveMeltQuote(updatedQuote).onSuccess {
                                val transaction = WalletTransaction(
                                    id = UUID.randomUUID().toString(),
                                    type = TransactionType.LIGHTNING_SEND,
                                    amount = quote.amount,
                                    unit = quote.unit,
                                    status = TransactionStatus.CONFIRMED,
                                    timestamp = Date(),
                                    description = "Lightning payment sent",
                                    quote = quote.id,
                                    fee = quote.feeReserve
                                )
                                repository.saveTransaction(transaction).onSuccess {
                                    onTransactionSaved()
                                    onBalanceRefresh()
                                    onPaymentComplete()
                                }.onFailure { error ->
                                    Log.e(TAG, "Failed to save transaction", error)
                                    _errorMessage.value = "Failed to save transaction: ${error.message}"
                                }
                            }.onFailure { error ->
                                Log.e(TAG, "Failed to save melt quote", error)
                                _errorMessage.value = "Failed to save quote: ${error.message}"
                            }
                        }
                    } else {
                        _errorMessage.value = "Payment failed"
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Payment failed: ${error.message}"
                }
            } finally {
                uiStateManager.setLoading(false)
            }
        }
    }
    
    /**
     * Check pending quotes for updates
     */
    suspend fun checkPendingQuotes(
        onTransactionSaved: () -> Unit,
        onBalanceRefresh: () -> Unit
    ) {
        // Check mint quotes
        repository.getMintQuotes().onSuccess { quotes ->
            val unpaidQuotes = quotes.filter { !it.paid }
            for (quote in unpaidQuotes) {
                cashuService.checkAndMintQuote(quote.id).onSuccess { success ->
                    if (success) {
                        // Update quote status and add transaction
                        val updatedQuote = quote.copy(paid = true, state = MintQuoteState.PAID)
                        repository.saveMintQuote(updatedQuote).onSuccess {
                            // Update currentMintQuote if it's the same quote
                            if (_currentMintQuote.value?.id == quote.id) {
                                _currentMintQuote.value = updatedQuote
                            }
                            
                            val transaction = WalletTransaction(
                                id = UUID.randomUUID().toString(),
                                type = TransactionType.LIGHTNING_RECEIVE,
                                amount = quote.amount,
                                unit = quote.unit,
                                status = TransactionStatus.CONFIRMED,
                                timestamp = Date(),
                                description = "Lightning payment received",
                                quote = quote.id
                            )
                            repository.saveTransaction(transaction).onSuccess {
                                onTransactionSaved()
                                onBalanceRefresh()
                                
                                Log.d(TAG, "Mint quote ${quote.id} was paid and minted")
                            }.onFailure { error ->
                                Log.e(TAG, "Failed to save transaction for mint quote ${quote.id}", error)
                            }
                        }.onFailure { error ->
                            Log.e(TAG, "Failed to save updated mint quote ${quote.id}", error)
                        }
                    }
                }
            }
            _pendingMintQuotes.value = unpaidQuotes
        }
        
        // Note: Melt quotes typically don't need polling as they're immediately processed
        repository.getMeltQuotes().onSuccess { quotes ->
            _pendingMeltQuotes.value = quotes.filter { it.state != MeltQuoteState.PAID }
        }
    }
    
    /**
     * Load pending quotes from repository (internal suspend method)
     */
    private suspend fun loadPendingQuotesInternal() {
        repository.getMintQuotes().onSuccess { quotes ->
            _pendingMintQuotes.value = quotes.filter { !it.paid }
        }
        
        repository.getMeltQuotes().onSuccess { quotes ->
            _pendingMeltQuotes.value = quotes.filter { it.state != MeltQuoteState.PAID }
        }
    }
    
    /**
     * Load pending quotes from repository (public method for external calls)
     */
    fun loadPendingQuotes() {
        coroutineScope.launch {
            loadPendingQuotesInternal()
        }
    }
    
    /**
     * Set a specific mint quote as current (for reopening from transaction list)
     */
    fun setCurrentMintQuote(quoteId: String, onQuoteFound: () -> Unit) {
        coroutineScope.launch {
            repository.getMintQuotes().onSuccess { quotes ->
                val quote = quotes.find { it.id == quoteId }
                if (quote != null) {
                    _currentMintQuote.value = quote
                    onQuoteFound()
                }
            }
        }
    }
    
    /**
     * Clear current mint quote
     */
    fun clearCurrentMintQuote() {
        _currentMintQuote.value = null
    }
    
    /**
     * Clear current melt quote
     */
    fun clearCurrentMeltQuote() {
        _currentMeltQuote.value = null
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Get current mint quote
     */
    fun getCurrentMintQuote(): MintQuote? = _currentMintQuote.value
    
    /**
     * Get current melt quote
     */
    fun getCurrentMeltQuote(): MeltQuote? = _currentMeltQuote.value
} 