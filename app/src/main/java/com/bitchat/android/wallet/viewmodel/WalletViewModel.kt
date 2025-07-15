package com.bitchat.android.wallet.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.wallet.data.*
import com.bitchat.android.wallet.repository.WalletRepository
import com.bitchat.android.wallet.service.CashuService
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import java.math.BigDecimal
import java.util.*

/**
 * ViewModel for managing Cashu wallet state and operations
 * Now refactored to use specialized manager classes for better organization
 */
class WalletViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = WalletRepository.getInstance(application)
    private val cashuService = CashuService.getInstance()
    
    // Manager instances
    private val uiStateManager = UIStateManager()
    private val mintManager = MintManager(repository, cashuService, viewModelScope, uiStateManager)
    private val tokenManager = TokenManager(repository, cashuService, viewModelScope, uiStateManager)
    private val lightningManager = LightningManager(repository, cashuService, viewModelScope, uiStateManager)
    private val transactionManager = TransactionManager(repository, viewModelScope)
    
    companion object {
        private const val TAG = "WalletViewModel"
        private const val POLLING_INTERVAL = 5000L // 5 seconds
    }
    
    // Core wallet data
    private val _balance = MutableLiveData<Long>(0L)
    val balance: LiveData<Long> = _balance
    
    // State management
    private var pollingJob: kotlinx.coroutines.Job? = null
    
    // Expose LiveData from managers
    val mints: LiveData<List<Mint>> = mintManager.mints
    val activeMint: LiveData<String?> = mintManager.activeMint
    val transactions: LiveData<List<WalletTransaction>> = transactionManager.transactions
    val generatedToken: LiveData<String?> = tokenManager.generatedToken
    val decodedToken: LiveData<CashuToken?> = tokenManager.decodedToken
    val tokenInput: LiveData<String> = tokenManager.tokenInput
    val currentMintQuote: LiveData<MintQuote?> = lightningManager.currentMintQuote
    val currentMeltQuote: LiveData<MeltQuote?> = lightningManager.currentMeltQuote
    val pendingMintQuotes: LiveData<List<MintQuote>> = lightningManager.pendingMintQuotes
    val pendingMeltQuotes: LiveData<List<MeltQuote>> = lightningManager.pendingMeltQuotes
    val showSendDialog: LiveData<Boolean> = uiStateManager.showSendDialog
    val showReceiveDialog: LiveData<Boolean> = uiStateManager.showReceiveDialog
    val sendType: LiveData<SendType> = uiStateManager.sendType
    val receiveType: LiveData<ReceiveType> = uiStateManager.receiveType
    val showAddMintDialog: LiveData<Boolean> = uiStateManager.showAddMintDialog
    val showSuccessAnimation: LiveData<Boolean> = uiStateManager.showSuccessAnimation
    val successAnimationData: LiveData<SuccessAnimationData?> = uiStateManager.successAnimationData
    val showFailureAnimation: LiveData<Boolean> = uiStateManager.showFailureAnimation
    val failureAnimationData: LiveData<FailureAnimationData?> = uiStateManager.failureAnimationData
    val isLoading: LiveData<Boolean> = uiStateManager.isLoading
    val errorMessage: LiveData<String?> = uiStateManager.errorMessage
    
    enum class SendType { CASHU, LIGHTNING }
    enum class ReceiveType { CASHU, LIGHTNING }
    
    /**
     * Data for success animation display
     */
    data class SuccessAnimationData(
        val type: SuccessAnimationType,
        val amount: Long,
        val unit: String,
        val description: String
    )
    
    /**
     * Data for failure animation display
     */
    data class FailureAnimationData(
        val errorMessage: String,
        val operationType: String = "Token Receive"
    )
    
    enum class SuccessAnimationType {
        CASHU_RECEIVED,
        CASHU_SENT, 
        LIGHTNING_RECEIVED,
        LIGHTNING_SENT
    }
    
    init {
        loadInitialData()
        startPolling()
        initializeDefaultWallet()
    }
    
    /**
     * Initialize with default mint if no mints are configured
     */
    private fun initializeDefaultWallet() {
        mintManager.initializeDefaultWallet {
            refreshBalance()
        }
    }
    
    /**
     * Load initial wallet data 
     */
    private fun loadInitialData() {
        mintManager.loadMints()
        transactionManager.loadTransactions()
        lightningManager.loadPendingQuotes()
    }
    
    /**
     * Refresh wallet balance
     */
    fun refreshBalance() {
        viewModelScope.launch {
            try {
                cashuService.getBalance().onSuccess { balance ->
                    _balance.value = balance
                }.onFailure { error ->
                    Log.e(TAG, "Failed to get balance", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while refreshing balance", e)
            }
        }
    }
    
    /**
     * Start polling for quote updates
     */
    private fun startPolling() {
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    lightningManager.checkPendingQuotes(
                        onTransactionSaved = { transactionManager.loadTransactions() },
                        onBalanceRefresh = { refreshBalance() }
                    )
                    delay(POLLING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during polling", e)
                    delay(POLLING_INTERVAL)
                }
            }
        }
    }
    
    // UI Action Methods - Delegate to UIStateManager
    
    fun showSendDialog() = uiStateManager.showSendDialog()
    
    fun hideSendDialog() {
        uiStateManager.hideSendDialog()
        tokenManager.clearGeneratedToken()
        lightningManager.clearCurrentMeltQuote()
    }
    
    fun showReceiveDialog() = uiStateManager.showReceiveDialog()
    
    fun hideReceiveDialog() {
        uiStateManager.hideReceiveDialog()
        tokenManager.clearTokenInput()
        lightningManager.clearCurrentMintQuote()
    }
    
    fun setSendType(type: SendType) = uiStateManager.setSendType(type)
    
    fun setReceiveType(type: ReceiveType) = uiStateManager.setReceiveType(type)
    
    fun showAddMintDialog() = uiStateManager.showAddMintDialog()
    
    fun hideAddMintDialog() = uiStateManager.hideAddMintDialog()
    
    fun clearError() = uiStateManager.clearError()
    
    // Token input management - Delegate to TokenManager
    fun setTokenInput(token: String) = tokenManager.setTokenInput(token)
    
    fun clearTokenInput() = tokenManager.clearTokenInput()
    
    // Animation management - Delegate to UIStateManager
    fun showSuccessAnimation(animationData: SuccessAnimationData) = uiStateManager.showSuccessAnimation(animationData)
    
    fun hideSuccessAnimation() = uiStateManager.hideSuccessAnimation()
    
    fun showFailureAnimation(animationData: FailureAnimationData) = uiStateManager.showFailureAnimation(animationData)
    
    fun hideFailureAnimation() = uiStateManager.hideFailureAnimation()
    
    // Back navigation - Delegate to UIStateManager
    fun setBackHandler(handler: () -> Boolean) = uiStateManager.setBackHandler(handler)
    
    fun handleBackPress(): Boolean = uiStateManager.handleBackPress()
    
    /**
     * Set a specific mint quote as current (for reopening from transaction list)
     */
    fun setCurrentMintQuote(quoteId: String) {
        lightningManager.setCurrentMintQuote(quoteId) {
            uiStateManager.setReceiveType(ReceiveType.LIGHTNING)
            showReceiveDialog()
        }
    }
    
    // Wallet Operations - Delegate to appropriate managers
    
    /**
     * Create a Cashu token 
     */
    fun createCashuToken(amount: Long, memo: String? = null) {
        tokenManager.createCashuToken(
            amount = amount,
            memo = memo,
            onTransactionSaved = { transactionManager.loadTransactions() },
            onBalanceRefresh = { refreshBalance() }
        )
    }
    
    /**
     * Create a Cashu token for payments (with callbacks for /pay command)
     */
    fun createCashuTokenForPayment(
        amount: Long, 
        memo: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        tokenManager.createCashuTokenForPayment(
            amount = amount,
            memo = memo,
            onSuccess = onSuccess,
            onError = onError,
            onTransactionSaved = { transactionManager.loadTransactions() },
            onBalanceRefresh = { refreshBalance() }
        )
    }
    
    /**
     * Decode a Cashu token to show information
     */
    fun decodeCashuToken(token: String) = tokenManager.decodeCashuToken(token)
    
    /**
     * Receive a Cashu token
     */
    fun receiveCashuToken(token: String) {
        tokenManager.receiveCashuToken(
            token = token,
            currentMints = mintManager.getCurrentMints(),
            onSuccess = { animationData ->
                showSuccessAnimation(animationData)
                viewModelScope.launch {
                    delay(500)
                    hideReceiveDialog()
                }
            },
            onFailure = { failureData ->
                showFailureAnimation(failureData)
                viewModelScope.launch {
                    delay(500)
                    hideReceiveDialog()
                }
            },
            onTransactionSaved = { transactionManager.loadTransactions() },
            onBalanceRefresh = { refreshBalance() },
            onMintsUpdated = { mintManager.loadMints() }
        )
    }
    
    /**
     * Create Lightning mint quote (for receiving)
     */
    fun createMintQuote(amount: Long, description: String? = null) = lightningManager.createMintQuote(amount, description)
    
    /**
     * Create Lightning melt quote (for sending)
     */
    fun createMeltQuote(invoice: String) {
        lightningManager.createMeltQuote(invoice) {
            // Restart polling when a new melt quote is created
            startPolling()
        }
    }
    
    /**
     * Pay Lightning invoice (melt)
     */
    fun payLightningInvoice(quoteId: String) {
        lightningManager.payLightningInvoice(
            quoteId = quoteId,
            onTransactionSaved = { transactionManager.loadTransactions() },
            onBalanceRefresh = { refreshBalance() },
            onPaymentComplete = { hideSendDialog() }
        )
    }
    
    // Mint Management - Delegate to MintManager
    
    /**
     * Add a new mint
     */
    fun addMint(mintUrl: String, nickname: String) {
        mintManager.addMint(mintUrl, nickname) {
            hideAddMintDialog()
        }
    }
    
    /**
     * Set the active mint
     */
    fun setActiveMint(mintUrl: String) {
        mintManager.setActiveMint(mintUrl) {
            refreshBalance()
        }
    }
    
    /**
     * Update mint nickname
     */
    fun updateMintNickname(mintUrl: String, newNickname: String) = mintManager.updateMintNickname(mintUrl, newNickname)
    
    /**
     * Sync all mints - refresh mint information and keysets
     */
    fun syncAllMints() = mintManager.syncAllMints()
    
    /**
     * Clear all wallet data
     */
    fun clearAllWalletData() {
        viewModelScope.launch {
            try {
                uiStateManager.setLoading(true)
                pollingJob?.cancel()
                
                // Clear all data
                repository.clearAllData()
                
                // Reset state
                _balance.value = 0L
                
                // Restart polling
                startPolling()
                
                // Reload all data
                loadInitialData()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing wallet data", e)
                uiStateManager.setError("Failed to clear data: ${e.message}")
            } finally {
                uiStateManager.setLoading(false)
            }
        }
    }
    
    /**
     * Export wallet data (for backup/debugging)
     */
    fun exportWalletData() {
        viewModelScope.launch {
            try {
                val exportData = mapOf(
                    "mints" to mintManager.getCurrentMints(),
                    "transactions" to transactionManager.getCurrentTransactions(),
                    "activeMint" to mintManager.getCurrentActiveMint(),
                    "balance" to _balance.value,
                    "exportDate" to Date().toString(),
                    "version" to "1.0"
                )
                
                // TODO: Implement actual file export - for now just log
                Log.d(TAG, "Export data prepared: ${exportData.keys}")
                
                // In a real implementation, you would:
                // 1. Convert to JSON
                // 2. Save to external storage with user permission
                // 3. Or share via intent
                
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting wallet data", e)
                uiStateManager.setError("Failed to export data: ${e.message}")
            }
        }
    }
    
    // Utility function to format sats
    private fun formatSats(sats: Long): String {
        return when {
            sats >= 100_000_000 -> String.format("%.2f BTC", sats / 100_000_000.0)
            sats >= 1000 -> String.format("%,d sats", sats)
            else -> "$sats sats"
        }
    }
    
    /**
     * Open receive dialog with a pre-filled Cashu token (for external integrations like chat)
     */
    fun openReceiveDialogWithToken(tokenString: String) {
        viewModelScope.launch {
            try {
                // Clear any existing state first
                hideReceiveDialog()
                tokenManager.clearTokenInput()
                
                // Set the receive type to Cashu
                uiStateManager.setReceiveType(ReceiveType.CASHU)
                
                // Set the token input which will trigger decoding
                tokenManager.setTokenInput(tokenString)
                
                // Show the receive dialog
                uiStateManager.showReceiveDialog()
                
                Log.d(TAG, "Opened receive dialog with token: ${tokenString.take(20)}...")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error opening receive dialog with token", e)
                uiStateManager.setError("Failed to process token: ${e.message}")
            }
        }
    }
    
    /**
     * Open receive dialog with pre-parsed Cashu token data (immediate display)
     */
    fun openReceiveDialogWithParsedToken(parsedToken: com.bitchat.android.parsing.ParsedCashuToken) {
        try {
            // Clear any existing state first
            hideReceiveDialog()
            tokenManager.clearTokenInput()
            
            // Set the receive type to Cashu
            uiStateManager.setReceiveType(ReceiveType.CASHU)
            
            // Set the token input
            tokenManager.setTokenInput(parsedToken.originalString)
            
            // Create CashuToken from ParsedCashuToken immediately (no async needed)
            val cashuToken = com.bitchat.android.wallet.data.CashuToken(
                token = parsedToken.originalString,
                amount = java.math.BigDecimal(parsedToken.amount),
                unit = parsedToken.unit,
                mint = parsedToken.mintUrl,
                memo = parsedToken.memo
            )
            
            // Set the decoded token immediately
            tokenManager.setDecodedToken(cashuToken)
            
            // Show the receive dialog immediately
            uiStateManager.showReceiveDialog()
            
            Log.d(TAG, "Opened receive dialog with parsed token: ${parsedToken.amount} ${parsedToken.unit}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening receive dialog with parsed token", e)
            uiStateManager.setError("Failed to process token: ${e.message}")
        }
    }
    
    /**
     * Get full transaction history (not just last 10)
     */
    fun getAllTransactions(): LiveData<List<WalletTransaction>> = transactionManager.getAllTransactions()
}
