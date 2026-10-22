package com.bitchat.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.services.VerificationService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class VerificationTab { MyCode, Scan }

data class VerificationUiState(
    val selectedTab: VerificationTab,
    val nickname: String,
    /** Encodes this device's identity for the other side to scan. */
    val myQrString: String,
    /** The peer the screen was opened for is verified, so it can be unverified here. */
    val canUnverify: Boolean,
)

sealed interface VerificationAction {
    data class SelectTab(val tab: VerificationTab) : VerificationAction
    data class CodeScanned(val code: String) : VerificationAction
    data object Unverify : VerificationAction
}

/**
 * QR verification: this device's code, the scanner, and unverifying the peer
 * the screen was opened for.
 *
 * [peerID] is that peer, or null when the screen was opened from somewhere
 * with no conversation, such as a deep link.
 */
@HiltViewModel(assistedFactory = VerificationViewModel.Factory::class)
class VerificationViewModel @AssistedInject constructor(
    @Assisted private val peerID: String?,
    @ApplicationContext context: Context,
    state: ChatState,
    private val verificationHandler: VerificationHandler,
    private val mesh: ChatSessionMesh,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(peerID: String?): VerificationViewModel
    }

    private val selectedTab = MutableStateFlow(VerificationTab.MyCode)

    // Read once: the identity does not change while the screen is open.
    private val npub: String? = try {
        NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
    } catch (_: Exception) {
        null
    }

    // Built once per nickname, not per emission. Each build past the service's
    // one-minute cache signs a new nonce, and the code must not change under
    // the other side's camera because a tab was switched.
    private val myQr = state.nickname.map { nickname -> nickname to qrStringFor(nickname) }

    val uiState: StateFlow<VerificationUiState> = combine(
        selectedTab,
        myQr,
        verificationHandler.verifiedFingerprints,
    ) { tab, (nickname, qr), verified ->
        uiStateFor(tab, nickname, qr, verified)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = uiStateFor(
            selectedTab.value,
            state.nickname.value,
            qrStringFor(state.nickname.value),
            verificationHandler.verifiedFingerprints.value,
        ),
    )

    fun onAction(action: VerificationAction) {
        when (action) {
            is VerificationAction.SelectTab -> selectedTab.value = action.tab
            is VerificationAction.CodeScanned -> {
                val qr = VerificationService.verifyScannedQR(action.code)
                // Back to this device's code, which the other side now scans.
                if (qr != null && verificationHandler.beginQRVerification(qr)) {
                    selectedTab.value = VerificationTab.MyCode
                }
            }
            VerificationAction.Unverify -> peerID?.let(verificationHandler::unverifyFingerprint)
        }
    }

    private fun qrStringFor(nickname: String): String =
        VerificationService.buildMyQRString(nickname, npub) ?: ""

    private fun uiStateFor(
        tab: VerificationTab,
        nickname: String,
        myQrString: String,
        verifiedFingerprints: Set<String>,
    ) = VerificationUiState(
        selectedTab = tab,
        nickname = nickname,
        myQrString = myQrString,
        canUnverify = peerID
            ?.let { mesh.unified.getPeerFingerprint(it) }
            ?.let { it in verifiedFingerprints } == true,
    )
}
