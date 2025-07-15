package com.bitchat.android.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.bitchat.android.wallet.data.CashuToken
import com.bitchat.android.wallet.data.Mint
import com.bitchat.android.wallet.data.WalletTransaction
import com.bitchat.android.wallet.data.TransactionType
import com.bitchat.android.wallet.data.TransactionStatus
import com.bitchat.android.wallet.repository.WalletRepository
import com.bitchat.android.wallet.service.CashuService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.util.*

/**
 * Manages all token-related operations for the wallet
 */
class TokenManager(
    private val repository: WalletRepository,
    private val cashuService: CashuService,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "TokenManager"
    }
    
    // Observable data
    private val _generatedToken = MutableLiveData<String?>(null)
    val generatedToken: androidx.lifecycle.LiveData<String?> = _generatedToken
    
    private val _decodedToken = MutableLiveData<CashuToken?>(null)
    val decodedToken: androidx.lifecycle.LiveData<CashuToken?> = _decodedToken
    
    private val _tokenInput = MutableLiveData<String>("")
    val tokenInput: androidx.lifecycle.LiveData<String> = _tokenInput
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: androidx.lifecycle.LiveData<Boolean> = _isLoading
    
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: androidx.lifecycle.LiveData<String?> = _errorMessage
    
    /**
     * Create a Cashu token 
     */
    fun createCashuToken(
        amount: Long, 
        memo: String? = null,
        onTransactionSaved: () -> Unit,
        onBalanceRefresh: () -> Unit
    ) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                cashuService.createToken(amount, memo).onSuccess { token ->
                    _generatedToken.value = token
                    
                    // Add transaction
                    val transaction = WalletTransaction(
                        id = UUID.randomUUID().toString(),
                        type = TransactionType.CASHU_SEND,
                        amount = BigDecimal(amount),
                        unit = "sat",
                        status = TransactionStatus.CONFIRMED,
                        timestamp = Date(),
                        description = memo ?: "Cashu token sent",
                        token = token
                    )
                    repository.saveTransaction(transaction).onSuccess {
                        onTransactionSaved()
                        onBalanceRefresh()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to save transaction", error)
                        _errorMessage.value = "Failed to save transaction: ${error.message}"
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Failed to create token: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Create a Cashu token for payments (with callbacks for /pay command)
     */
    fun createCashuTokenForPayment(
        amount: Long, 
        memo: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
        onTransactionSaved: () -> Unit,
        onBalanceRefresh: () -> Unit
    ) {
        coroutineScope.launch {
            try {
                cashuService.createToken(amount, memo).onSuccess { token ->
                    
                    // Add transaction
                    val transaction = WalletTransaction(
                        id = UUID.randomUUID().toString(),
                        type = TransactionType.CASHU_SEND,
                        amount = BigDecimal(amount),
                        unit = "sat",
                        status = TransactionStatus.CONFIRMED,
                        timestamp = Date(),
                        description = memo ?: "Cashu token sent",
                        token = token
                    )
                    
                    repository.saveTransaction(transaction).onSuccess {
                        onTransactionSaved()
                        onBalanceRefresh()
                        
                        // Success callback with token
                        onSuccess(token)
                        
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to save transaction", error)
                        onError("Failed to save transaction: ${error.message}")
                    }
                    
                }.onFailure { error ->
                    onError("Failed to create token: ${error.message}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception creating payment token", e)
                onError(e.message ?: "Unknown error occurred")
            }
        }
    }
    
    /**
     * Decode a Cashu token to show information
     */
    fun decodeCashuToken(token: String) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                cashuService.decodeToken(token).onSuccess { decodedToken ->
                    _decodedToken.value = decodedToken
                }.onFailure { error ->
                    _errorMessage.value = "Invalid token: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Receive a Cashu token
     */
    fun receiveCashuToken(
        token: String,
        currentMints: List<Mint>,
        onSuccess: (WalletViewModel.SuccessAnimationData) -> Unit,
        onFailure: (WalletViewModel.FailureAnimationData) -> Unit,
        onTransactionSaved: () -> Unit,
        onBalanceRefresh: () -> Unit,
        onMintsUpdated: () -> Unit
    ) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                
                // Get decoded token
                val decodedToken = _decodedToken.value
                if (decodedToken == null) {
                    val failureData = WalletViewModel.FailureAnimationData(
                        errorMessage = "Failed to decode token",
                        operationType = "Token Receive"
                    )
                    onFailure(failureData)
                    clearTokenInput()
                    return@launch
                }

                // Use the CashuService.receiveToken with mint management
                cashuService.receiveToken(
                    token = token,
                    autoAdd = true, // Enable automatic mint addition
                    currentMints = currentMints,
                    decodedToken = decodedToken
                ).onSuccess { amount ->
                    
                    // Add transaction
                    val transaction = WalletTransaction(
                        id = UUID.randomUUID().toString(),
                        type = TransactionType.CASHU_RECEIVE,
                        amount = BigDecimal(amount),
                        unit = "sat",
                        status = TransactionStatus.CONFIRMED,
                        timestamp = Date(),
                        description = "Cashu token received",
                        token = token
                    )
                    repository.saveTransaction(transaction).onSuccess {
                        onTransactionSaved()
                        onBalanceRefresh()
                        // Reload mints in case a new one was added
                        onMintsUpdated()
                        
                        // Clear token input immediately
                        clearTokenInput()
                        
                        // Show success animation
                        val animationData = WalletViewModel.SuccessAnimationData(
                            type = WalletViewModel.SuccessAnimationType.CASHU_RECEIVED,
                            amount = amount,
                            unit = "sat",
                            description = "Cashu token received successfully!"
                        )
                        onSuccess(animationData)
                        
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to save transaction", error)
                        
                        // Clear token input
                        clearTokenInput()
                        
                        // Show failure animation for transaction save error
                        val failureData = WalletViewModel.FailureAnimationData(
                            errorMessage = "Failed to save transaction: ${error.message}",
                            operationType = "Token Receive"
                        )
                        onFailure(failureData)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to receive token", error)
                    
                    // Clear token input
                    clearTokenInput()
                    
                    // Show failure animation for token receive error
                    val failureData = WalletViewModel.FailureAnimationData(
                        errorMessage = error.message ?: "Unknown error occurred",
                        operationType = "Token Receive"
                    )
                    onFailure(failureData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in receiveCashuToken", e)
                
                // Clear token input
                clearTokenInput()
                
                // Show failure animation for unexpected error
                val failureData = WalletViewModel.FailureAnimationData(
                    errorMessage = e.message ?: "Unexpected error occurred",
                    operationType = "Token Receive"
                )
                onFailure(failureData)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Set token input and trigger decoding if it's a Cashu token
     */
    fun setTokenInput(token: String) {
        _tokenInput.value = token
        if (token.isNotEmpty() && token.startsWith("cashu")) {
            decodeCashuToken(token)
        }
    }
    
    /**
     * Clear token input and decoded token
     */
    fun clearTokenInput() {
        _tokenInput.value = ""
        _decodedToken.value = null
    }
    
    /**
     * Clear generated token
     */
    fun clearGeneratedToken() {
        _generatedToken.value = null
    }
    
    /**
     * Set decoded token directly (for external integrations)
     */
    fun setDecodedToken(token: CashuToken) {
        _decodedToken.value = token
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Get current token input
     */
    fun getCurrentTokenInput(): String = _tokenInput.value ?: ""
    
    /**
     * Get current decoded token
     */
    fun getCurrentDecodedToken(): CashuToken? = _decodedToken.value
    
    /**
     * Get current generated token
     */
    fun getCurrentGeneratedToken(): String? = _generatedToken.value
} 