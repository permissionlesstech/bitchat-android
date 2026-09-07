package com.bitchat.android.board

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object BoardWireConstants {
    const val POST_ID_LENGTH = 16
    const val SIGNING_KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64
    const val TRANSPORT_SENDER_ID_LENGTH = 8
    const val CONTENT_MAX_BYTES = 512
    const val NICKNAME_MAX_BYTES = 64
    const val GEOHASH_MAX_LENGTH = 12
    const val MAX_LIFETIME_MS: ULong = 604_800_000uL
    const val POST_SIGNING_CONTEXT = "bitchat-board-v1"
    const val TOMBSTONE_SIGNING_CONTEXT = "bitchat-board-del-v1"
    const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
}

class BoardPostPacket(
    val postID: ByteArray,
    val geohash: String,
    val content: String,
    val authorSigningKey: ByteArray,
    val authorNickname: String,
    val createdAt: ULong,
    val expiresAt: ULong,
    val flags: UByte,
    val signature: ByteArray
) {
    val isUrgent: Boolean
        get() = (flags.toInt() and URGENT_FLAG.toInt()) != 0

    val signingBytes: ByteArray
        get() = signingBytes(
            postID = postID,
            geohash = geohash,
            content = content,
            authorSigningKey = authorSigningKey,
            authorNickname = authorNickname,
            createdAt = createdAt,
            expiresAt = expiresAt,
            flags = flags
        )

    fun verifySignature(): Boolean =
        BoardWireCodec.verify(signature, signingBytes, authorSigningKey)

    override fun equals(other: Any?): Boolean =
        other is BoardPostPacket &&
            postID.contentEquals(other.postID) &&
            geohash == other.geohash &&
            content == other.content &&
            authorSigningKey.contentEquals(other.authorSigningKey) &&
            authorNickname == other.authorNickname &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            flags == other.flags &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int {
        var result = postID.contentHashCode()
        result = 31 * result + geohash.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + authorSigningKey.contentHashCode()
        result = 31 * result + authorNickname.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + flags.hashCode()
        return 31 * result + signature.contentHashCode()
    }

    companion object {
        const val URGENT_FLAG: UByte = 0x01u

        fun signingBytes(
            postID: ByteArray,
            geohash: String,
            content: String,
            authorSigningKey: ByteArray,
            authorNickname: String,
            createdAt: ULong,
            expiresAt: ULong,
            flags: UByte
        ): ByteArray = ByteArrayOutputStream().apply {
            appendContext(BoardWireConstants.POST_SIGNING_CONTEXT)
            write(postID)
            appendLengthPrefixed(geohash.toByteArray(Charsets.UTF_8))
            appendLengthPrefixed(content.toByteArray(Charsets.UTF_8))
            write(authorSigningKey)
            appendLengthPrefixed(authorNickname.toByteArray(Charsets.UTF_8))
            appendULong(createdAt)
            appendULong(expiresAt)
            write(flags.toInt())
        }.toByteArray()
    }
}

class BoardTombstonePacket(
    val postID: ByteArray,
    val authorSigningKey: ByteArray,
    val deletedAt: ULong,
    val signature: ByteArray
) {
    val signingBytes: ByteArray
        get() = signingBytes(postID, deletedAt)

    fun verifySignature(): Boolean =
        BoardWireCodec.verify(signature, signingBytes, authorSigningKey)

    override fun equals(other: Any?): Boolean =
        other is BoardTombstonePacket &&
            postID.contentEquals(other.postID) &&
            authorSigningKey.contentEquals(other.authorSigningKey) &&
            deletedAt == other.deletedAt &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int {
        var result = postID.contentHashCode()
        result = 31 * result + authorSigningKey.contentHashCode()
        result = 31 * result + deletedAt.hashCode()
        return 31 * result + signature.contentHashCode()
    }

    companion object {
        fun signingBytes(postID: ByteArray, deletedAt: ULong): ByteArray =
            ByteArrayOutputStream().apply {
                appendContext(BoardWireConstants.TOMBSTONE_SIGNING_CONTEXT)
                write(postID)
                appendULong(deletedAt)
            }.toByteArray()
    }
}

sealed interface BoardWire {
    data class Post(val packet: BoardPostPacket) : BoardWire
    data class Tombstone(val packet: BoardTombstonePacket) : BoardWire

    fun verifySignature(): Boolean = when (this) {
        is Post -> packet.verifySignature()
        is Tombstone -> packet.verifySignature()
    }
}

/**
 * Board payloads authenticate their embedded author key, so their outer mesh
 * sender must not expose the device's stable peer ID. The pseudonym remains
 * stable only for one board signing identity.
 */
fun BoardWire.transportSenderID(): ByteArray {
    val authorKey = when (this) {
        is BoardWire.Post -> packet.authorSigningKey
        is BoardWire.Tombstone -> packet.authorSigningKey
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(authorKey)
        .copyOf(BoardWireConstants.TRANSPORT_SENDER_ID_LENGTH)
}

object BoardWireCodec {
    private const val TLV_KIND = 0x01
    private const val TLV_POST_ID = 0x02
    private const val TLV_GEOHASH = 0x03
    private const val TLV_CONTENT = 0x04
    private const val TLV_AUTHOR_SIGNING_KEY = 0x05
    private const val TLV_AUTHOR_NICKNAME = 0x06
    private const val TLV_CREATED_AT = 0x07
    private const val TLV_EXPIRES_AT = 0x08
    private const val TLV_FLAGS = 0x09
    private const val TLV_SIGNATURE = 0x0A
    private const val TLV_DELETED_AT = 0x0B
    private const val KIND_POST = 0x01
    private const val KIND_TOMBSTONE = 0x02

    fun encode(wire: BoardWire): ByteArray = ByteArrayOutputStream().apply {
        when (wire) {
            is BoardWire.Post -> with(wire.packet) {
                appendTlv(TLV_KIND, byteArrayOf(KIND_POST.toByte()))
                appendTlv(TLV_POST_ID, postID)
                appendTlv(TLV_GEOHASH, geohash.toByteArray(Charsets.UTF_8))
                appendTlv(TLV_CONTENT, content.toByteArray(Charsets.UTF_8))
                appendTlv(TLV_AUTHOR_SIGNING_KEY, authorSigningKey)
                appendTlv(TLV_AUTHOR_NICKNAME, authorNickname.toByteArray(Charsets.UTF_8))
                appendTlv(TLV_CREATED_AT, createdAt.toBigEndianBytes())
                appendTlv(TLV_EXPIRES_AT, expiresAt.toBigEndianBytes())
                appendTlv(TLV_FLAGS, byteArrayOf(flags.toByte()))
                appendTlv(TLV_SIGNATURE, signature)
            }

            is BoardWire.Tombstone -> with(wire.packet) {
                appendTlv(TLV_KIND, byteArrayOf(KIND_TOMBSTONE.toByte()))
                appendTlv(TLV_POST_ID, postID)
                appendTlv(TLV_AUTHOR_SIGNING_KEY, authorSigningKey)
                appendTlv(TLV_DELETED_AT, deletedAt.toBigEndianBytes())
                appendTlv(TLV_SIGNATURE, signature)
            }
        }
    }.toByteArray()

    fun decode(data: ByteArray): BoardWire? {
        var offset = 0
        var kind: Int? = null
        var postID: ByteArray? = null
        var geohash: String? = null
        var content: String? = null
        var contentBytes = 0
        var authorSigningKey: ByteArray? = null
        var authorNickname: String? = null
        var nicknameBytes = 0
        var createdAt: ULong? = null
        var expiresAt: ULong? = null
        var flags: UByte? = null
        var signature: ByteArray? = null
        var deletedAt: ULong? = null

        while (offset + 3 <= data.size) {
            val type = data[offset].toInt() and 0xFF
            offset += 1
            val length =
                ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (length > data.size - offset) return null
            val value = data.copyOfRange(offset, offset + length)
            offset += length

            when (type) {
                TLV_KIND -> {
                    if (value.size != 1) return null
                    kind = value[0].toInt() and 0xFF
                }
                TLV_POST_ID -> {
                    if (value.size != BoardWireConstants.POST_ID_LENGTH) return null
                    postID = value
                }
                TLV_GEOHASH -> {
                    if (value.size > BoardWireConstants.GEOHASH_MAX_LENGTH) return null
                    geohash = decodeUtf8(value) ?: return null
                }
                TLV_CONTENT -> {
                    if (value.size > BoardWireConstants.CONTENT_MAX_BYTES) return null
                    contentBytes = value.size
                    content = decodeUtf8(value) ?: return null
                }
                TLV_AUTHOR_SIGNING_KEY -> {
                    if (value.size != BoardWireConstants.SIGNING_KEY_LENGTH) return null
                    authorSigningKey = value
                }
                TLV_AUTHOR_NICKNAME -> {
                    if (value.size > BoardWireConstants.NICKNAME_MAX_BYTES) return null
                    nicknameBytes = value.size
                    authorNickname = decodeUtf8(value) ?: return null
                }
                TLV_CREATED_AT -> createdAt = value.toULongBigEndian() ?: return null
                TLV_EXPIRES_AT -> expiresAt = value.toULongBigEndian() ?: return null
                TLV_FLAGS -> {
                    if (value.size != 1) return null
                    flags = value[0].toUByte()
                }
                TLV_SIGNATURE -> {
                    if (value.size != BoardWireConstants.SIGNATURE_LENGTH) return null
                    signature = value
                }
                TLV_DELETED_AT -> deletedAt = value.toULongBigEndian() ?: return null
            }
        }

        val requiredPostID = postID ?: return null
        val requiredKey = authorSigningKey ?: return null
        val requiredSignature = signature ?: return null
        return when (kind) {
            KIND_POST -> {
                val requiredGeohash = geohash ?: return null
                val requiredContent = content ?: return null
                val requiredNickname = authorNickname ?: return null
                val requiredCreatedAt = createdAt ?: return null
                val requiredExpiresAt = expiresAt ?: return null
                val requiredFlags = flags ?: return null
                if (contentBytes !in 1..BoardWireConstants.CONTENT_MAX_BYTES) return null
                if (nicknameBytes > BoardWireConstants.NICKNAME_MAX_BYTES) return null
                if (!isValidGeohash(requiredGeohash)) return null
                if (requiredExpiresAt <= requiredCreatedAt) return null
                if (requiredExpiresAt - requiredCreatedAt > BoardWireConstants.MAX_LIFETIME_MS) return null
                BoardWire.Post(
                    BoardPostPacket(
                        postID = requiredPostID,
                        geohash = requiredGeohash,
                        content = requiredContent,
                        authorSigningKey = requiredKey,
                        authorNickname = requiredNickname,
                        createdAt = requiredCreatedAt,
                        expiresAt = requiredExpiresAt,
                        flags = requiredFlags,
                        signature = requiredSignature
                    )
                )
            }

            KIND_TOMBSTONE -> BoardWire.Tombstone(
                BoardTombstonePacket(
                    postID = requiredPostID,
                    authorSigningKey = requiredKey,
                    deletedAt = deletedAt ?: return null,
                    signature = requiredSignature
                )
            )

            else -> null
        }
    }

    fun urgentFlag(data: ByteArray): Boolean {
        var offset = 0
        while (offset + 3 <= data.size) {
            val type = data[offset].toInt() and 0xFF
            offset += 1
            val length =
                ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (length > data.size - offset) return false
            if (type == TLV_FLAGS && length == 1) {
                return (data[offset].toInt() and BoardPostPacket.URGENT_FLAG.toInt()) != 0
            }
            offset += length
        }
        return false
    }

    internal fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        try {
            if (signature.size != BoardWireConstants.SIGNATURE_LENGTH ||
                publicKey.size != BoardWireConstants.SIGNING_KEY_LENGTH
            ) {
                false
            } else {
                Ed25519Signer().run {
                    init(false, Ed25519PublicKeyParameters(publicKey, 0))
                    update(message, 0, message.size)
                    verifySignature(signature)
                }
            }
        } catch (_: Exception) {
            false
        }

    private fun isValidGeohash(value: String): Boolean =
        value.isEmpty() ||
            (value.length <= BoardWireConstants.GEOHASH_MAX_LENGTH &&
                value.all { it in BoardWireConstants.GEOHASH_ALPHABET })

    private fun decodeUtf8(value: ByteArray): String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString()
        } catch (_: Exception) {
            null
        }
}

private fun ByteArrayOutputStream.appendTlv(type: Int, value: ByteArray) {
    require(value.size <= 0xFFFF)
    write(type)
    write((value.size ushr 8) and 0xFF)
    write(value.size and 0xFF)
    write(value)
}

private fun ByteArrayOutputStream.appendContext(context: String) {
    val value = context.toByteArray(Charsets.UTF_8).take(255).toByteArray()
    write(value.size)
    write(value)
}

private fun ByteArrayOutputStream.appendLengthPrefixed(value: ByteArray) {
    val limited = value.take(0xFFFF).toByteArray()
    write((limited.size ushr 8) and 0xFF)
    write(limited.size and 0xFF)
    write(limited)
}

private fun ByteArrayOutputStream.appendULong(value: ULong) {
    write(value.toBigEndianBytes())
}

private fun ULong.toBigEndianBytes(): ByteArray =
    ByteArray(8) { index -> (this shr ((7 - index) * 8)).toByte() }

private fun ByteArray.toULongBigEndian(): ULong? {
    if (size != 8) return null
    var value = 0uL
    for (byte in this) {
        value = (value shl 8) or (byte.toULong() and 0xFFuL)
    }
    return value
}
