package com.bitchat.android.wallet.viewmodel

import androidx.lifecycle.MutableLiveData

/**
 * Manages all UI state for the wallet
 */
class UIStateManager {
    
    // Send/Receive dialog state
    private val _showSendDialog = MutableLiveData<Boolean>(false)
    val showSendDialog: androidx.lifecycle.LiveData<Boolean> = _showSendDialog
    
    private val _showReceiveDialog = MutableLiveData<Boolean>(false)
    val showReceiveDialog: androidx.lifecycle.LiveData<Boolean> = _showReceiveDialog
    
    private val _sendType = MutableLiveData<WalletViewModel.SendType>(WalletViewModel.SendType.CASHU)
    val sendType: androidx.lifecycle.LiveData<WalletViewModel.SendType> = _sendType
    
    private val _receiveType = MutableLiveData<WalletViewModel.ReceiveType>(WalletViewModel.ReceiveType.CASHU)
    val receiveType: androidx.lifecycle.LiveData<WalletViewModel.ReceiveType> = _receiveType
    
    // Mints tab state  
    private val _showAddMintDialog = MutableLiveData<Boolean>(false)
    val showAddMintDialog: androidx.lifecycle.LiveData<Boolean> = _showAddMintDialog
    
    // Success animation state
    private val _showSuccessAnimation = MutableLiveData<Boolean>(false)
    val showSuccessAnimation: androidx.lifecycle.LiveData<Boolean> = _showSuccessAnimation
    
    private val _successAnimationData = MutableLiveData<WalletViewModel.SuccessAnimationData?>(null)
    val successAnimationData: androidx.lifecycle.LiveData<WalletViewModel.SuccessAnimationData?> = _successAnimationData
    
    // Failure animation state
    private val _showFailureAnimation = MutableLiveData<Boolean>(false)
    val showFailureAnimation: androidx.lifecycle.LiveData<Boolean> = _showFailureAnimation
    
    private val _failureAnimationData = MutableLiveData<WalletViewModel.FailureAnimationData?>(null)
    val failureAnimationData: androidx.lifecycle.LiveData<WalletViewModel.FailureAnimationData?> = _failureAnimationData
    
    // Loading and error state
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: androidx.lifecycle.LiveData<Boolean> = _isLoading
    
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: androidx.lifecycle.LiveData<String?> = _errorMessage
    
    // Back navigation handler
    private var backHandler: (() -> Boolean)? = null
    
    // Dialog Management
    
    fun showSendDialog() {
        _showSendDialog.value = true
    }
    
    fun hideSendDialog() {
        _showSendDialog.value = false
        _sendType.value = WalletViewModel.SendType.CASHU
    }
    
    fun showReceiveDialog() {
        _showReceiveDialog.value = true
    }
    
    fun hideReceiveDialog() {
        _showReceiveDialog.value = false
        _receiveType.value = WalletViewModel.ReceiveType.CASHU
    }
    
    fun setSendType(type: WalletViewModel.SendType) {
        _sendType.value = type
    }
    
    fun setReceiveType(type: WalletViewModel.ReceiveType) {
        _receiveType.value = type
    }
    
    fun showAddMintDialog() {
        _showAddMintDialog.value = true
    }
    
    fun hideAddMintDialog() {
        _showAddMintDialog.value = false
    }
    
    // Animation Management
    
    fun showSuccessAnimation(animationData: WalletViewModel.SuccessAnimationData) {
        _successAnimationData.value = animationData
        _showSuccessAnimation.value = true
    }
    
    fun hideSuccessAnimation() {
        _showSuccessAnimation.value = false
        _successAnimationData.value = null
    }
    
    fun showFailureAnimation(animationData: WalletViewModel.FailureAnimationData) {
        _failureAnimationData.value = animationData
        _showFailureAnimation.value = true
    }
    
    fun hideFailureAnimation() {
        _showFailureAnimation.value = false
        _failureAnimationData.value = null
    }
    
    // Loading and Error Management
    
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    fun setError(message: String) {
        _errorMessage.value = message
    }
    
    // Back Navigation
    
    fun setBackHandler(handler: () -> Boolean) {
        backHandler = handler
    }
    
    fun handleBackPress(): Boolean {
        return backHandler?.invoke() ?: false
    }
    
    // State Getters
    
    fun getCurrentSendType(): WalletViewModel.SendType = _sendType.value ?: WalletViewModel.SendType.CASHU
    
    fun getCurrentReceiveType(): WalletViewModel.ReceiveType = _receiveType.value ?: WalletViewModel.ReceiveType.CASHU
    
    fun isCurrentlyLoading(): Boolean = _isLoading.value ?: false
    
    fun getCurrentError(): String? = _errorMessage.value
} 