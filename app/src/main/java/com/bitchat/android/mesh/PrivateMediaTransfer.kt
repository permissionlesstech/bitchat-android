package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import java.util.concurrent.atomic.AtomicBoolean

enum class PrivateMediaWireMode {
    ENCRYPTED_NOISE_0X20,
    SIGNED_DIRECTED_RAW_0X22
}

class PreparedPrivateMediaTransfer internal constructor(
    val transferId: String,
    val wireMode: PrivateMediaWireMode,
    private val commitAction: () -> Boolean
) {
    private val committed = AtomicBoolean(false)

    /** A prepared transfer is single-use, including after a failed commit. */
    fun commit(): Boolean {
        if (!committed.compareAndSet(false, true)) return false
        return commitAction()
    }
}

sealed interface PrivateMediaPreparation {
    data class Ready(val transfer: PreparedPrivateMediaTransfer) : PrivateMediaPreparation
    data class RequiresLegacyConsent(val warning: String) : PrivateMediaPreparation
    data object NeedsHandshake : PrivateMediaPreparation
    data object AwaitingPeerState : PrivateMediaPreparation
    data class Rejected(val reason: String) : PrivateMediaPreparation
}

internal data class BuiltPrivateMediaTransfer(
    val packet: BitchatPacket,
    val fragments: List<BitchatPacket>,
    val wireMode: PrivateMediaWireMode
)

internal sealed interface PrivateMediaBuildOutcome {
    data class Ready(val built: BuiltPrivateMediaTransfer) : PrivateMediaBuildOutcome
    data class RequiresLegacyConsent(val warning: String) : PrivateMediaBuildOutcome
    data object NeedsHandshake : PrivateMediaBuildOutcome
    data object AwaitingPeerState : PrivateMediaBuildOutcome
    data class Rejected(val reason: String) : PrivateMediaBuildOutcome
}

internal sealed interface PrivateMediaEncryptionResult {
    data class Success(val ciphertext: ByteArray) : PrivateMediaEncryptionResult
    data object GenerationChanged : PrivateMediaEncryptionResult
    data object Failed : PrivateMediaEncryptionResult
}

/** Builds, routes, signs, and fragments exactly once before UI local echo. */
internal class PrivateMediaTransferPreparer(
    private val senderID: ByteArray,
    private val ttl: UByte,
    private val policyProvider: (String) -> PrivateMediaPolicyDecision,
    private val encrypt: (
        ByteArray,
        String,
        AuthenticatedNoiseSession
    ) -> PrivateMediaEncryptionResult,
    private val finalizeRoutedAndSigned: (BitchatPacket) -> BitchatPacket?,
    private val fragment: (BitchatPacket, Int) -> List<BitchatPacket>,
    private val now: () -> ULong = { System.currentTimeMillis().toULong() }
) {
    fun prepare(
        recipientPeerID: String,
        recipientID: ByteArray,
        file: BitchatFilePacket,
        allowLegacyFallback: Boolean
    ): PrivateMediaBuildOutcome = prepare(
        recipientPeerID,
        recipientID,
        file,
        allowLegacyFallback,
        generationRetriesRemaining = 1
    )

    private fun prepare(
        recipientPeerID: String,
        recipientID: ByteArray,
        file: BitchatFilePacket,
        allowLegacyFallback: Boolean,
        generationRetriesRemaining: Int
    ): PrivateMediaBuildOutcome {
        val maxPrivateFragments =
            com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        val absolutePayloadUpperBound =
            maxPrivateFragments.toLong() *
                com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE.toLong()
        // The file content alone cannot exceed the total bytes carried by every
        // possible fragment. Reject before TLV encoding, encryption, signing,
        // and packet serialization make additional full-size copies.
        if (file.content.size.toLong() > absolutePayloadUpperBound) {
            return PrivateMediaBuildOutcome.Rejected(
                "File exceeds the private-media v1 limit of 256 final mesh fragments"
            )
        }

        val policy = policyProvider(recipientPeerID)
        val mode = when (policy) {
            is PrivateMediaPolicyDecision.Encrypted -> PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
            PrivateMediaPolicyDecision.RequiresLegacyConsent -> {
                if (!allowLegacyFallback) {
                    return PrivateMediaBuildOutcome.RequiresLegacyConsent(
                        "This older client cannot receive encrypted private media. " +
                            "Sending this one file will expose its contents to mesh relays, " +
                            "although the directed packet will still be signed."
                    )
                }
                PrivateMediaWireMode.SIGNED_DIRECTED_RAW_0X22
            }
            PrivateMediaPolicyDecision.NeedsHandshake ->
                return PrivateMediaBuildOutcome.NeedsHandshake
            PrivateMediaPolicyDecision.AwaitingPeerState ->
                return PrivateMediaBuildOutcome.AwaitingPeerState
            is PrivateMediaPolicyDecision.Blocked ->
                return PrivateMediaBuildOutcome.Rejected(policy.reason)
        }

        val filePayload = file.encode()
            ?: return PrivateMediaBuildOutcome.Rejected("Failed to encode private media")

        val packet = when (mode) {
            PrivateMediaWireMode.ENCRYPTED_NOISE_0X20 -> {
                val plaintext = NoisePayload(NoisePayloadType.FILE_TRANSFER, filePayload).encode()
                val encryption = try {
                    encrypt(
                        plaintext,
                        recipientPeerID,
                        (policy as PrivateMediaPolicyDecision.Encrypted).authenticatedSession
                    )
                } catch (_: Exception) {
                    PrivateMediaEncryptionResult.Failed
                }
                val ciphertext = when (encryption) {
                    is PrivateMediaEncryptionResult.Success -> encryption.ciphertext
                    PrivateMediaEncryptionResult.GenerationChanged -> {
                        if (generationRetriesRemaining > 0) {
                            return prepare(
                                recipientPeerID,
                                recipientID,
                                file,
                                allowLegacyFallback,
                                generationRetriesRemaining - 1
                            )
                        }
                        return PrivateMediaBuildOutcome.AwaitingPeerState
                    }
                    PrivateMediaEncryptionResult.Failed ->
                        return PrivateMediaBuildOutcome.Rejected(
                            "The authenticated Noise session could not encrypt this file"
                        )
                }
                BitchatPacket(
                    version = if (ciphertext.size > 0xFFFF) 2u else 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = senderID.copyOf(),
                    recipientID = recipientID.copyOf(),
                    timestamp = now(),
                    payload = ciphertext,
                    signature = null,
                    ttl = ttl
                )
            }

            PrivateMediaWireMode.SIGNED_DIRECTED_RAW_0X22 -> BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = senderID.copyOf(),
                recipientID = recipientID.copyOf(),
                timestamp = now(),
                payload = filePayload,
                signature = null,
                ttl = ttl
            )
        }

        val finalized = finalizeRoutedAndSigned(packet)
            ?: return PrivateMediaBuildOutcome.Rejected(
                if (mode == PrivateMediaWireMode.SIGNED_DIRECTED_RAW_0X22) {
                    "Could not sign the legacy private-media packet; nothing was sent"
                } else {
                    "Could not sign the encrypted private-media packet; nothing was sent"
                }
            )
        if (finalized.signature?.size != 64) {
            return PrivateMediaBuildOutcome.Rejected(
                "Could not produce a valid Ed25519 private-media signature; nothing was sent"
            )
        }

        val fragments = fragment(finalized, maxPrivateFragments)
        if (fragments.isEmpty()) {
            return PrivateMediaBuildOutcome.Rejected(
                "File exceeds the private-media v1 limit of 256 final mesh fragments"
            )
        }
        if (fragments.size > maxPrivateFragments) {
            return PrivateMediaBuildOutcome.Rejected(
                "File exceeds the private-media v1 limit of 256 final mesh fragments"
            )
        }

        return PrivateMediaBuildOutcome.Ready(
            BuiltPrivateMediaTransfer(finalized, fragments, mode)
        )
    }
}
