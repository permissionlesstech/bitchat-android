package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.services.ContactDirectory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the security sheet shows for one conversation. */
data class SecurityVerificationUiState(
    val peerID: String,
    val displayName: String,
    val fingerprint: String?,
    val myFingerprint: String,
    val isVerified: Boolean,
    val sessionState: String?,
    /** No fingerprint yet, and a mesh peer a handshake can reach. */
    val canStartHandshake: Boolean,
)

sealed interface SecurityVerificationAction {
    data object StartHandshake : SecurityVerificationAction
    data class Verify(val fingerprint: String) : SecurityVerificationAction
    data class Unverify(val fingerprint: String) : SecurityVerificationAction
}

/**
 * Session security and fingerprints for one private conversation.
 *
 * [conversationID] is the id the sheet was opened with. It is canonicalized
 * again on every selection change, since the conversation may have resolved to
 * a contact after the sheet opened.
 */
@HiltViewModel(assistedFactory = SecurityVerificationViewModel.Factory::class)
class SecurityVerificationViewModel @AssistedInject constructor(
    @Assisted private val conversationID: String,
    state: ChatState,
    private val verificationHandler: VerificationHandler,
    private val mesh: ChatSessionMesh,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(conversationID: String): SecurityVerificationViewModel
    }

    private val peerID = state.selectedPrivateChatPeer.map {
        ContactDirectory.canonicalConversationId(conversationID)
    }

    val uiState: StateFlow<SecurityVerificationUiState> = combine(
        peerID,
        verificationHandler.verifiedFingerprints,
        state.peerSessionStates,
        state.peerFingerprints,
    ) { peerID, verified, sessionStates, _ ->
        uiStateFor(peerID, verified, sessionStates)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = uiStateFor(
            ContactDirectory.canonicalConversationId(conversationID),
            verificationHandler.verifiedFingerprints.value,
            state.peerSessionStates.value,
        ),
    )

    fun onAction(action: SecurityVerificationAction) {
        when (action) {
            SecurityVerificationAction.StartHandshake ->
                mesh.unified.initiateNoiseHandshake(uiState.value.peerID)
            is SecurityVerificationAction.Verify ->
                verificationHandler.verifyFingerprintValue(action.fingerprint)
            is SecurityVerificationAction.Unverify ->
                verificationHandler.unverifyFingerprintValue(action.fingerprint)
        }
    }

    private fun uiStateFor(
        peerID: String,
        verifiedFingerprints: Set<String>,
        peerSessionStates: Map<String, String>,
    ): SecurityVerificationUiState {
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return SecurityVerificationUiState(
            peerID = peerID,
            displayName = verificationHandler.resolvePeerDisplayNameForFingerprint(peerID),
            fingerprint = fingerprint,
            myFingerprint = verificationHandler.getMyFingerprint(),
            isVerified = fingerprint != null && fingerprint in verifiedFingerprints,
            sessionState = resolveConversationSessionState(
                conversationID = peerID,
                activeMeshPeerID = ContactDirectory.resolve(peerID).meshPeerID,
                peerSessionStates = peerSessionStates,
            ),
            canStartHandshake = fingerprint == null && peerID.matches(MeshPeerIdPattern),
        )
    }

    private companion object {
        val MeshPeerIdPattern = Regex("^[0-9a-fA-F]{16}$")
    }
}
