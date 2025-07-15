package com.bitchat.android.wallet.viewmodel

import androidx.lifecycle.MutableLiveData
import com.bitchat.android.wallet.data.WalletTransaction
import com.bitchat.android.wallet.repository.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages all transaction-related operations for the wallet
 */
class TransactionManager(
    private val repository: WalletRepository,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "TransactionManager"
    }
    
    // Observable data
    private val _transactions = MutableLiveData<List<WalletTransaction>>(emptyList())
    val transactions: androidx.lifecycle.LiveData<List<WalletTransaction>> = _transactions
    
    /**
     * Load recent transaction history (last 10)
     */
    fun loadTransactions() {
        coroutineScope.launch {
            repository.getLastTransactions(10).onSuccess { txList ->
                _transactions.value = txList
            }
        }
    }
    
    /**
     * Get full transaction history (not just last 10)
     */
    fun getAllTransactions(): androidx.lifecycle.LiveData<List<WalletTransaction>> {
        val allTransactions = MutableLiveData<List<WalletTransaction>>()
        coroutineScope.launch {
            repository.getAllTransactions().onSuccess { txList ->
                allTransactions.value = txList
            }
        }
        return allTransactions
    }
    
    /**
     * Save a new transaction
     */
    fun saveTransaction(transaction: WalletTransaction, onSuccess: () -> Unit, onError: (String) -> Unit) {
        coroutineScope.launch {
            repository.saveTransaction(transaction).onSuccess {
                loadTransactions() // Refresh the transaction list
                onSuccess()
            }.onFailure { error ->
                onError(error.message ?: "Failed to save transaction")
            }
        }
    }
    
    /**
     * Get current transactions list
     */
    fun getCurrentTransactions(): List<WalletTransaction> = _transactions.value ?: emptyList()
} 