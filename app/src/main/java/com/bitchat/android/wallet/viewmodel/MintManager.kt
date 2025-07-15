package com.bitchat.android.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.wallet.data.Mint
import com.bitchat.android.wallet.repository.WalletRepository
import com.bitchat.android.wallet.service.CashuService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages all mint-related operations for the wallet
 */
class MintManager(
    private val repository: WalletRepository,
    private val cashuService: CashuService,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "MintManager"
    }
    
    // Observable data
    private val _mints = MutableLiveData<List<Mint>>(emptyList())
    val mints: androidx.lifecycle.LiveData<List<Mint>> = _mints
    
    private val _activeMint = MutableLiveData<String?>(null)
    val activeMint: androidx.lifecycle.LiveData<String?> = _activeMint
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: androidx.lifecycle.LiveData<Boolean> = _isLoading
    
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: androidx.lifecycle.LiveData<String?> = _errorMessage
    
    /**
     * Initialize with default mint if no mints are configured
     */
    fun initializeDefaultWallet(onWalletInitialized: () -> Unit) {
        coroutineScope.launch {
            try {
                // Check if we have mints configured
                repository.getMints().onSuccess { mintList ->
                    if (mintList.isEmpty()) {
                        Log.d(TAG, "No mints found, initializing with default mint")
                        // Add default mint
                        addDefaultMint(onWalletInitialized)
                    } else {
                        _mints.value = mintList
                    }
                }
                
                // Ensure we have an active mint
                repository.getActiveMint().onSuccess { activeMintUrl ->
                    if (activeMintUrl.isNullOrEmpty()) {
                        Log.d(TAG, "No active mint, setting default")
                        // Use default mint URL from CashuService
                        cashuService.initializeWallet("https://testnut.cashu.space").onSuccess {
                            onWalletInitialized()
                        }
                    } else {
                        _activeMint.value = activeMintUrl
                        initializeWalletWithMint(activeMintUrl, onWalletInitialized)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing default wallet", e)
                _errorMessage.value = "Failed to initialize wallet: ${e.message}"
            }
        }
    }
    
    /**
     * Add default mint for testing
     */
    private fun addDefaultMint(onWalletInitialized: () -> Unit) {
        coroutineScope.launch {
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
                        initializeWalletWithMint(defaultMintUrl, onWalletInitialized)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to add default mint", error)
                    // Fallback - still initialize wallet even if mint info fails
                    cashuService.initializeWallet(defaultMintUrl).onSuccess {
                        _activeMint.value = defaultMintUrl
                        onWalletInitialized()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception adding default mint", e)
                _errorMessage.value = "Failed to add default mint: ${e.message}"
            }
        }
    }
    
    /**
     * Initialize wallet with specific mint
     */
    private suspend fun initializeWalletWithMint(mintUrl: String, onWalletInitialized: () -> Unit) {
        try {
            _isLoading.value = true
            cashuService.initializeWallet(mintUrl).onSuccess {
                onWalletInitialized()
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
     * Load mints from repository
     */
    fun loadMints() {
        coroutineScope.launch {
            repository.getMints().onSuccess { mintList ->
                _mints.value = mintList
            }
            
            repository.getActiveMint().onSuccess { mintUrl ->
                _activeMint.value = mintUrl
            }
        }
    }
    
    /**
     * Add a new mint
     */
    fun addMint(mintUrl: String, nickname: String, onSuccess: () -> Unit = {}) {
        coroutineScope.launch {
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
                        
                        onSuccess()
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
    fun setActiveMint(mintUrl: String, onWalletInitialized: () -> Unit = {}) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                repository.setActiveMint(mintUrl).onSuccess {
                    _activeMint.value = mintUrl
                    initializeWalletWithMint(mintUrl, onWalletInitialized)
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
        coroutineScope.launch {
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
        coroutineScope.launch {
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
                            repository.saveMint(updatedMint).onSuccess {
                                // Mint updated successfully
                            }.onFailure { error ->
                                Log.e(TAG, "Failed to save updated mint ${mint.url}", error)
                            }
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
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Get current active mint URL
     */
    fun getCurrentActiveMint(): String? = _activeMint.value
    
    /**
     * Get current mints list
     */
    fun getCurrentMints(): List<Mint> = _mints.value ?: emptyList()
} 