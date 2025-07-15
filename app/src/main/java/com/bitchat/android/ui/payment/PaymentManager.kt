package com.bitchat.android.ui.payment

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.bitchat.android.wallet.viewmodel.WalletViewModel
import com.bitchat.android.wallet.data.TransactionType
import com.bitchat.android.wallet.data.TransactionStatus
import com.bitchat.android.wallet.data.WalletTransaction
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.*

/**
 * Manages payment creation and status for the /pay command
 * Coordinates between ChatViewModel, WalletViewModel, and UI
 */
class PaymentManager(
    private val coroutineScope: CoroutineScope,
    private val walletViewModel: WalletViewModel
) {
    
    companion object {
        private const val TAG = "PaymentManager"
    }
    
    // Payment status state
    private val _paymentStatus = MutableStateFlow<PaymentStatus?>(null)
    val paymentStatus: StateFlow<PaymentStatus?> = _paymentStatus.asStateFlow()
    
    /**
     * Create a Cashu token payment for the specified amount
     */
    fun createPayment(
        amount: Long,
        memo: String? = null,
        onTokenCreated: (String) -> Unit
    ) {
        Log.d(TAG, "Creating payment for $amount sats")
        
        // Set creating status
        _paymentStatus.value = PaymentStatus.Creating(amount)
        
        coroutineScope.launch {
            try {
                // Use WalletViewModel's createCashuToken method
                walletViewModel.createCashuTokenForPayment(
                    amount = amount,
                    memo = memo,
                    onSuccess = { token ->
                        Log.d(TAG, "Payment token created successfully: ${token.take(20)}...")
                        
                        // Set success status
                        _paymentStatus.value = PaymentStatus.Success(amount, token)
                        
                        // Notify caller with the token
                        onTokenCreated(token)
                    },
                    onError = { error ->
                        Log.e(TAG, "Payment creation failed: $error")
                        
                        // Set error status
                        _paymentStatus.value = PaymentStatus.Error(amount, error)
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception creating payment", e)
                _paymentStatus.value = PaymentStatus.Error(
                    amount, 
                    e.message ?: "Unknown error occurred"
                )
            }
        }
    }
    
    /**
     * Clear the current payment status
     */
    fun clearStatus() {
        _paymentStatus.value = null
    }
    
    /**
     * Check if a payment is currently being created
     */
    fun isCreatingPayment(): Boolean {
        return _paymentStatus.value is PaymentStatus.Creating
    }
} 