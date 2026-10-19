package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageRouterResetToken

internal class AccountResetLease internal constructor(
    internal val id: AccountResetLeaseId,
    internal val transport: NostrTransport?,
    internal val transportToken: NostrTransportResetToken?,
    internal val router: MessageRouter?,
    internal val routerToken: MessageRouterResetToken?,
    internal val relay: NostrRelayManager,
    internal val relayToken: RelayAccountResetToken
) {
    internal var relayDiscarded = false
    internal var completionAttempted = false
}

/**
 * Owns the cross-component account barrier used by panic and process exit.
 *
 * All production account resets enter here. This makes the individual reset
 * tokens one composite lease and prevents an older panic from installing or
 * starting a replacement mesh after a newer panic or quit has begun.
 */
internal object AccountResetCoordinator {
    private val gate = AccountResetGate()

    fun begin(
        application: Application,
        terminal: Boolean = false
    ): AccountResetLease? {
        var result: AccountResetLease? = null
        gate.begin(terminal = terminal) { id ->
            val transport = NostrTransport.tryGetInstance()
            val router = MessageRouter.tryGetInstance()
            val transportToken = transport?.discardForAccountReset()
            val routerToken = router?.discardForAccountReset()

            // Invalidate callbacks before removing relay subscriptions. The
            // runtime call also cancels account-bound jobs when initialized.
            NostrInboundAccountLifecycle.invalidate()
            NostrBackgroundRuntime.invalidateAccount()

            val relay = NostrRelayManager.getInstance(application)
            val relayToken = relay.beginAccountReset()
            result = AccountResetLease(
                id = id,
                transport = transport,
                transportToken = transportToken,
                router = router,
                routerToken = routerToken,
                relay = relay,
                relayToken = relayToken
            )
        } ?: return null
        return result
    }

    /**
     * Remove relay-owned state after the NDR runtime has quiesced.
     */
    fun discardRelay(lease: AccountResetLease): Boolean {
        var discarded = false
        val owned = gate.runIfCurrent(lease.id) {
            discarded = lease.relay.discardForAccountReset(lease.relayToken)
            if (discarded) {
                lease.relayDiscarded = true
            }
        }
        return owned && discarded
    }

    /**
     * Install and start the replacement identity while this lease still owns
     * the reset.
     */
    fun complete(
        lease: AccountResetLease,
        installReplacement: () -> MeshService,
        startReplacement: (MeshService) -> Unit
    ): Boolean {
        var reopened = false
        val owned = gate.runIfCurrent(lease.id) {
            if (!lease.relayDiscarded || lease.completionAttempted) {
                return@runIfCurrent
            }
            lease.completionAttempted = true
            val replacement = installReplacement()
            lease.transport?.senderPeerID = replacement.myPeerID
            lease.router?.installReplacementMeshForAccountReset(replacement)

            val relayReopened =
                lease.relay.completeAccountReset(lease.relayToken)
            val transportReopened =
                relayReopened &&
                    (lease.transportToken?.let { token ->
                        lease.transport?.completeAccountReset(token) == true
                    } ?: true)
            val routerReopened =
                transportReopened &&
                    (lease.routerToken?.let { token ->
                        lease.router?.completeAccountReset(token) == true
                    } ?: true)

            if (routerReopened && transportReopened && relayReopened) {
                NostrBackgroundRuntime.resetSubscriptions()
                startReplacement(replacement)
                reopened = true
            }
        }
        return owned && reopened
    }
}
