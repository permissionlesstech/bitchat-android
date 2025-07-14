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
 */
class WalletViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = WalletRepository.getInstance(application)
    private val cashuService = CashuService.getInstance()
    
    companion object {
        private const val TAG = "WalletViewModel"
        private const val POLLING_INTERVAL = 5000L // 5 seconds
    }
    
    // Observable data
    private val _balance = MutableLiveData<Long>(0L)
    val balance: LiveData<Long> = _balance
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage
    
    private val _mints = MutableLiveData<List<Mint>>(emptyList())
    val mints: LiveData<List<Mint>> = _mints
    
    private val _activeMint = MutableLiveData<String?>(null)
    val activeMint: LiveData<String?> = _activeMint
    
    private val _transactions = MutableLiveData<List<WalletTransaction>>(emptyList())
    val transactions: LiveData<List<WalletTransaction>> = _transactions
    
    private val _pendingMintQuotes = MutableLiveData<List<MintQuote>>(emptyList())
    val pendingMintQuotes: LiveData<List<MintQuote>> = _pendingMintQuotes
    
    private val _pendingMeltQuotes = MutableLiveData<List<MeltQuote>>(emptyList())
    val pendingMeltQuotes: LiveData<List<MeltQuote>> = _pendingMeltQuotes
    
    // Send/Receive dialog state
    private val _showSendDialog = MutableLiveData<Boolean>(false)
    val showSendDialog: LiveData<Boolean> = _showSendDialog
    
    private val _showReceiveDialog = MutableLiveData<Boolean>(false)
    val showReceiveDialog: LiveData<Boolean> = _showReceiveDialog
    
    private val _sendType = MutableLiveData<SendType>(SendType.CASHU)
    val sendType: LiveData<SendType> = _sendType
    
    private val _receiveType = MutableLiveData<ReceiveType>(ReceiveType.CASHU)
    val receiveType: LiveData<ReceiveType> = _receiveType
    
    // Mints tab state  
    private val _showAddMintDialog = MutableLiveData<Boolean>(false)
    val showAddMintDialog: LiveData<Boolean> = _showAddMintDialog
    
    // Current operations state
    private val _generatedToken = MutableLiveData<String?>(null)
    val generatedToken: LiveData<String?> = _generatedToken
    
    private val _decodedToken = MutableLiveData<CashuToken?>(null)
    val decodedToken: LiveData<CashuToken?> = _decodedToken
    
    private val _currentMintQuote = MutableLiveData<MintQuote?>(null)
    val currentMintQuote: LiveData<MintQuote?> = _currentMintQuote
    
    private val _currentMeltQuote = MutableLiveData<MeltQuote?>(null)
    val currentMeltQuote: LiveData<MeltQuote?> = _currentMeltQuote
    
    // State management
    private var pollingJob: kotlinx.coroutines.Job? = null
    
    enum class SendType { CASHU, LIGHTNING }
    enum class ReceiveType { CASHU, LIGHTNING }
    
    init {
        loadInitialData()
        startPolling()
        initializeDefaultWallet()
    }
    
    /**
     * Initialize with default mint if no mints are configured
     */
    private fun initializeDefaultWallet() {
        viewModelScope.launch {
            try {
                // Check if we have mints configured
                repository.getMints().onSuccess { mintList ->
                    if (mintList.isEmpty()) {
                        Log.d(TAG, "No mints found, initializing with default mint")
                        // Add default mint
                        addDefaultMint()
                    }
                }
                
                // Ensure we have an active mint
                repository.getActiveMint().onSuccess { activeMintUrl ->
                    if (activeMintUrl.isNullOrEmpty()) {
                        Log.d(TAG, "No active mint, setting default")
                        // Use default mint URL from CashuService
                        cashuService.initializeWallet("https://testnut.cashu.space").onSuccess {
                            refreshBalance()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing default wallet", e)
            }
        }
    }
    
    /**
     * Add default mint for testing
     */
    private fun addDefaultMint() {
        viewModelScope.launch {
            val defaultMintUrl = "https://mint.minibits.cash/Bitcoin"
            val defaultMintNickname = "Minibits"
            
            try {
                cashuService.getMintInfo(defaultMintUrl).onSuccess { mintInfo ->
                    val mint = Mint(
                        url = defaultMintUrl,
                        nickname = defaultMintNickname,
                        info = mintInfo,
                        keysets = emptyList(),
                        active = true,
                        dateAdded = Date()
                    )
                    
                    repository.saveMint(mint).onSuccess {
                        repository.setActiveMint(defaultMintUrl)
                        _activeMint.value = defaultMintUrl
                        repository.getMints().onSuccess { mintList ->
                            _mints.value = mintList
                        }
                        // Initialize wallet with this mint
                        initializeWalletWithMint(defaultMintUrl)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to add default mint", error)
                    // Fallback - still initialize wallet even if mint info fails
                    cashuService.initializeWallet(defaultMintUrl).onSuccess {
                        _activeMint.value = defaultMintUrl
                        refreshBalance()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception adding default mint", e)
            }
        }
    }
    
    /**
     * Load initial wallet data 
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                // Load mints
                repository.getMints().onSuccess { mintList ->
                    _mints.value = mintList
                }
                
                // Load active mint and initialize wallet
                repository.getActiveMint().onSuccess { mintUrl ->
                    if (!mintUrl.isNullOrEmpty()) {
                        _activeMint.value = mintUrl
                        initializeWalletWithMint(mintUrl)
                    } else {
                        // Load mints first and set first as active if none selected
                        repository.getMints().onSuccess { mintList ->
                            _mints.value = mintList
                            if (mintList.isNotEmpty()) {
                                val firstMint = mintList.first().url
                                setActiveMint(firstMint)
                            }
                        }
                    }
                }
                
                // Load transactions
                loadTransactions()
                
                // Load pending quotes
                loadPendingQuotes()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load initial data", e)
                _errorMessage.value = "Failed to load wallet data: ${e.message}"
            }
        }
    }
    
    /**
     * Initialize wallet with specific mint
     */
    private suspend fun initializeWalletWithMint(mintUrl: String) {
        try {
            _isLoading.value = true
            cashuService.initializeWallet(mintUrl).onSuccess {
                refreshBalance()
                Log.d(TAG, "Wallet initialized with mint: $mintUrl")
            }.onFailure { error ->
                Log.e(TAG, "Failed to initialize wallet with mint: $mintUrl", error)
                _errorMessage.value = "Failed to connect to mint: ${error.message}"
            }
        } finally {
            _isLoading.value = false
        }
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
                    checkPendingQuotes()
                    delay(POLLING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during polling", e)
                    delay(POLLING_INTERVAL)
                }
            }
        }
    }
    
    /**
     * Check pending quotes for updates
     */
    private suspend fun checkPendingQuotes() {
        // Check mint quotes
        repository.getMintQuotes().onSuccess { quotes ->
            val unpaidQuotes = quotes.filter { !it.paid }
            for (quote in unpaidQuotes) {
                cashuService.checkAndMintQuote(quote.id).onSuccess { success ->
                    if (success) {
                        // Update quote status and add transaction
                        val updatedQuote = quote.copy(paid = true, state = MintQuoteState.PAID)
                        repository.saveMintQuote(updatedQuote)
                        
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
                        repository.saveTransaction(transaction)
                        
                        loadTransactions()
                        refreshBalance()
                        
                        Log.d(TAG, "Mint quote ${quote.id} was paid and minted")
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
     * Load transaction history
     */
    private fun loadTransactions() {
        viewModelScope.launch {
            repository.getLastTransactions(10).onSuccess { txList ->
                _transactions.value = txList
            }
        }
    }
    
    /**
     * Load pending quotes
     */
    private fun loadPendingQuotes() {
        viewModelScope.launch {
            repository.getMintQuotes().onSuccess { quotes ->
                _pendingMintQuotes.value = quotes.filter { !it.paid }
            }
            
            repository.getMeltQuotes().onSuccess { quotes ->
                _pendingMeltQuotes.value = quotes.filter { it.state != MeltQuoteState.PAID }
            }
        }
    }
    
    // UI Action Methods
    
    fun showSendDialog() {
        _showSendDialog.value = true
    }
    
    fun hideSendDialog() {
        _showSendDialog.value = false
        _generatedToken.value = null
        _currentMeltQuote.value = null
        _sendType.value = SendType.CASHU
    }
    
    fun showReceiveDialog() {
        _showReceiveDialog.value = true
    }
    
    fun hideReceiveDialog() {
        _showReceiveDialog.value = false
        _decodedToken.value = null
        _currentMintQuote.value = null
        _receiveType.value = ReceiveType.CASHU
    }
    
    fun setSendType(type: SendType) {
        _sendType.value = type
    }
    
    fun setReceiveType(type: ReceiveType) {
        _receiveType.value = type
    }
    
    fun showAddMintDialog() {
        _showAddMintDialog.value = true
    }
    
    fun hideAddMintDialog() {
        _showAddMintDialog.value = false
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Set a specific mint quote as current (for reopening from transaction list)
     */
    fun setCurrentMintQuote(quoteId: String) {
        viewModelScope.launch {
            repository.getMintQuotes().onSuccess { quotes ->
                val quote = quotes.find { it.id == quoteId }
                if (quote != null) {
                    _currentMintQuote.value = quote
                    _receiveType.value = ReceiveType.LIGHTNING
                    showReceiveDialog()
                }
            }
        }
    }
    
    // Wallet Operations
    
    /**
     * Create a Cashu token 
     */
    fun createCashuToken(amount: Long, memo: String? = null) {
        viewModelScope.launch {
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
                    repository.saveTransaction(transaction)
                    
                    loadTransactions()
                    refreshBalance()
                }.onFailure { error ->
                    _errorMessage.value = "Failed to create token: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Decode a Cashu token to show information
     */
    fun decodeCashuToken(token: String) {
        viewModelScope.launch {
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
    fun receiveCashuToken(token: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                cashuService.receiveToken(token).onSuccess { amount ->
                    
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
                    repository.saveTransaction(transaction)
                    
                    loadTransactions()
                    refreshBalance()
                    hideReceiveDialog()
                }.onFailure { error ->
                    _errorMessage.value = "Failed to receive token: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Create Lightning mint quote (for receiving)
     */
    fun createMintQuote(amount: Long, description: String? = null) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                cashuService.createMintQuote(amount, description).onSuccess { quote ->
                    _currentMintQuote.value = quote
                    
                    // Save quote for tracking
                    repository.saveMintQuote(quote)
                    loadPendingQuotes()
                }.onFailure { error ->
                    _errorMessage.value = "Failed to create invoice: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Create Lightning melt quote (for sending)
     */
    fun createMeltQuote(invoice: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                cashuService.createMeltQuote(invoice).onSuccess { quote ->
                    _currentMeltQuote.value = quote
                    
                    // Save quote for tracking
                    repository.saveMeltQuote(quote)
                    loadPendingQuotes()
                    
                    // kick off the polling
                    startPolling()
                }.onFailure { error ->
                    _errorMessage.value = "Failed to process invoice: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Pay Lightning invoice (melt)
     */
    fun payLightningInvoice(quoteId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                cashuService.payInvoice(quoteId).onSuccess { success ->
                    if (success) {
                        // Update quote status and add transaction
                        val quote = _currentMeltQuote.value
                        if (quote != null) {
                            val updatedQuote = quote.copy(paid = true, state = MeltQuoteState.PAID)
                            repository.saveMeltQuote(updatedQuote)
                            
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
                            repository.saveTransaction(transaction)
                            
                            loadTransactions()
                            refreshBalance()
                            hideSendDialog()
                        }
                    } else {
                        _errorMessage.value = "Payment failed"
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Payment failed: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    // Mint Management
    
    /**
     * Add a new mint
     */
    fun addMint(mintUrl: String, nickname: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                cashuService.getMintInfo(mintUrl).onSuccess { mintInfo ->
                    val mint = Mint(
                        url = mintUrl,
                        nickname = nickname.ifEmpty { mintInfo.name },
                        info = mintInfo,
                        keysets = emptyList(), // Will be populated by CDK
                        active = true,
                        dateAdded = Date()
                    )
                    
                    repository.saveMint(mint).onSuccess {
                        // Reload mints
                        repository.getMints().onSuccess { mintList ->
                            _mints.value = mintList
                        }
                        
                        // Set as active mint if it's the first one
                        if (_activeMint.value.isNullOrEmpty()) {
                            setActiveMint(mintUrl)
                        }
                        
                        hideAddMintDialog()
                    }.onFailure { error ->
                        _errorMessage.value = "Failed to save mint: ${error.message}"
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Failed to connect to mint: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Set the active mint
     */
    fun setActiveMint(mintUrl: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                repository.setActiveMint(mintUrl).onSuccess {
                    _activeMint.value = mintUrl
                    initializeWalletWithMint(mintUrl)
                }.onFailure { error ->
                    _errorMessage.value = "Failed to set active mint: ${error.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Update mint nickname
     */
    fun updateMintNickname(mintUrl: String, newNickname: String) {
        viewModelScope.launch {
            val currentMints = _mints.value ?: emptyList()
            val mintToUpdate = currentMints.find { it.url == mintUrl }
            if (mintToUpdate != null) {
                val updatedMint = mintToUpdate.copy(nickname = newNickname)
                repository.saveMint(updatedMint).onSuccess {
                    // Reload mints
                    repository.getMints().onSuccess { mintList ->
                        _mints.value = mintList
                    }
                }.onFailure { error ->
                    _errorMessage.value = "Failed to update mint nickname: ${error.message}"
                }
            }
        }
    }
    
    /**
     * Sync all mints - refresh mint information and keysets
     */
    fun syncAllMints() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val currentMints = _mints.value ?: emptyList()
                
                for (mint in currentMints) {
                    try {
                        cashuService.getMintInfo(mint.url).onSuccess { mintInfo ->
                            val updatedMint = mint.copy(
                                info = mintInfo,
                                lastSync = Date()
                            )
                            repository.saveMint(updatedMint)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing mint ${mint.url}", e)
                    }
                }
                
                // Reload mints list
                repository.getMints().onSuccess { mintList ->
                    _mints.value = mintList
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing mints", e)
                _errorMessage.value = "Failed to sync mints: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Clear all wallet data
     */
    fun clearAllWalletData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                pollingJob?.cancel()
                
                // Clear all data
                repository.clearAllData()
                
                // Reset state
                _balance.value = 0L
                _mints.value = emptyList()
                _activeMint.value = null
                _transactions.value = emptyList()
                _pendingMintQuotes.value = emptyList()
                _pendingMeltQuotes.value = emptyList()
                _currentMintQuote.value = null
                _currentMeltQuote.value = null
                _generatedToken.value = null
                _decodedToken.value = null
                
                // Restart polling
                startPolling()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing wallet data", e)
                _errorMessage.value = "Failed to clear data: ${e.message}"
            } finally {
                _isLoading.value = false
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
                    "mints" to _mints.value,
                    "transactions" to _transactions.value,
                    "activeMint" to _activeMint.value,
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
                _errorMessage.value = "Failed to export data: ${e.message}"
            }
        }
    }
    
    /**
     * Get full transaction history (not just last 10)
     */
    fun getAllTransactions(): LiveData<List<WalletTransaction>> {
        val allTransactions = MutableLiveData<List<WalletTransaction>>()
        viewModelScope.launch {
            repository.getAllTransactions().onSuccess { txList ->
                allTransactions.value = txList
            }
        }
        return allTransactions
    }
}
