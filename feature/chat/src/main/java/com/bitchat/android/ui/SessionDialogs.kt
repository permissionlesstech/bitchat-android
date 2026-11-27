package com.bitchat.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.defaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bitchat.android.navigation.GenerationViewModelStoreOwner

/**
 * The session's dialogs, drawn once above the navigation display so they show over whichever
 * destination is on top. A full-screen destination covers chat, which then leaves composition,
 * so a dialog drawn by chat would wait unseen until the user went back to it.
 *
 * Each dialog is its own window and takes Back while it shows.
 *
 * The ViewModel lives in [viewModelStores] with the screens' ViewModels, so a new session discards
 * it with them and the next one reads the new session.
 */
@Composable
fun SessionDialogs(viewModelStores: GenerationViewModelStoreOwner) {
    val host = checkNotNull(LocalViewModelStoreOwner.current)
    key(viewModelStores.generation) {
        val store = viewModelStores.viewModelStore
        val owner = remember(store, host) { SessionViewModelStoreOwner(store, host) }
        val viewModel: SessionDialogsViewModel = hiltViewModel(owner)
        val dialogs by viewModel.uiState.collectAsStateWithLifecycle()
        SessionDialogsContent(dialogs, viewModel::onAction)
    }
}

/**
 * The screens' store with the host's factory and creation extras, which is what makes a Hilt
 * ViewModel work there. The store is retained, so it cannot hold the factory itself.
 */
private class SessionViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val host: ViewModelStoreOwner,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = host.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras: CreationExtras
        get() = host.defaultViewModelCreationExtras
}

@Composable
internal fun SessionDialogsContent(
    dialogs: SessionDialogsState,
    onAction: (SessionDialogAction) -> Unit,
) {
    ChannelPasswordPrompt(
        showPrompt = dialogs.showPasswordPrompt,
        promptChannel = dialogs.passwordPromptChannel,
        onSubmit = { channel, password ->
            onAction(SessionDialogAction.SubmitChannelPassword(channel, password))
        },
        onDismiss = { onAction(SessionDialogAction.DismissPasswordPrompt) },
    )
    dialogs.legacyPrivateMediaConsent?.let { request ->
        LegacyPrivateMediaConsentDialog(
            request = request,
            onApprove = { onAction(SessionDialogAction.ApproveLegacyPrivateMedia(request.requestId)) },
            onCancel = { onAction(SessionDialogAction.CancelLegacyPrivateMedia(request.requestId)) },
        )
    }
}

/**
 * Shows the channel password dialog for as long as the prompt state asks for it. The typed
 * password lives only while the prompt is open.
 */
@Composable
internal fun ChannelPasswordPrompt(
    showPrompt: Boolean,
    promptChannel: String?,
    onSubmit: (channel: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    dialog: @Composable (
        show: Boolean,
        channelName: String?,
        passwordInput: String,
        onPasswordChange: (String) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) -> Unit = { show, channelName, passwordInput, onPasswordChange, onConfirm, dismiss ->
        PasswordPromptDialog(show, channelName, passwordInput, onPasswordChange, onConfirm, dismiss)
    }
) {
    var passwordInput by remember(showPrompt, promptChannel) { mutableStateOf("") }

    dialog(
        showPrompt,
        promptChannel,
        passwordInput,
        { passwordInput = it },
        {
            if (promptChannel != null && passwordInput.isNotEmpty()) {
                onSubmit(promptChannel, passwordInput)
            }
        },
        onDismiss
    )
}
