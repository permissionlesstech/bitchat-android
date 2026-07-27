package com.bitchat.android.groups

import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer

data class GroupMember(
    val fingerprint: String,
    val signingKey: ByteArray,
    val nickname: String
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is GroupMember &&
                fingerprint == other.fingerprint &&
                signingKey.contentEquals(other.signingKey) &&
                nickname == other.nickname)

    override fun hashCode(): Int =
        31 * (31 * fingerprint.hashCode() + signingKey.contentHashCode()) + nickname.hashCode()
}

data class BitchatGroup(
    val groupID: ByteArray,
    val name: String,
    val epoch: Long,
    val members: List<GroupMember>,
    val creatorFingerprint: String
) {
    val peerID: String get() = GroupIds.peerID(groupID)
    val creator: GroupMember? get() = members.firstOrNull { it.fingerprint == creatorFingerprint }

    fun isMember(fingerprint: String): Boolean =
        members.any { it.fingerprint == fingerprint }

    fun memberWithSigningKey(signingKey: ByteArray): GroupMember? =
        members.firstOrNull { it.signingKey.contentEquals(signingKey) }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is BitchatGroup &&
                groupID.contentEquals(other.groupID) &&
                name == other.name &&
                epoch == other.epoch &&
                members == other.members &&
                creatorFingerprint == other.creatorFingerprint)

    override fun hashCode(): Int {
        var result = groupID.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + creatorFingerprint.hashCode()
        return result
    }

    companion object {
        const val MAX_MEMBERS = 16
        const val GROUP_ID_LENGTH = 16
        const val KEY_LENGTH = 32
        const val MAX_EPOCH = 0xffff_ffffL
    }
}

object GroupIds {
    private const val PREFIX = "group_"
    private val pattern = Regex("^group_[0-9a-f]{32}$")

    fun peerID(groupID: ByteArray): String {
        require(groupID.size == BitchatGroup.GROUP_ID_LENGTH)
        return PREFIX + groupID.hexEncodedString()
    }

    fun groupID(peerID: String): ByteArray? {
        val normalized = peerID.lowercase()
        if (!pattern.matches(normalized)) return null
        return normalized.removePrefix(PREFIX).dataFromHexString()
    }

    fun isGroup(peerID: String?): Boolean =
        peerID != null && pattern.matches(peerID.lowercase())
}

class GroupTlvValueTooLongException : IllegalArgumentException("group TLV value exceeds UInt16")

internal object GroupTLV {
    data class Field(val type: Int, val value: ByteArray)

    fun put(type: Int, value: ByteArray, output: ByteArrayOutputStream) {
        if (value.size > 0xffff) throw GroupTlvValueTooLongException()
        output.write(type and 0xff)
        output.write((value.size ushr 8) and 0xff)
        output.write(value.size and 0xff)
        output.write(value)
    }

    fun encode(vararg fields: Pair<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        fields.forEach { (type, value) -> put(type, value, output) }
        return output.toByteArray()
    }

    fun parse(data: ByteArray): List<Field>? {
        val fields = mutableListOf<Field>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 3 > data.size) return null
            val type = data[offset].toInt() and 0xff
            val length =
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                    (data[offset + 2].toInt() and 0xff)
            offset += 3
            if (offset + length > data.size) return null
            fields += Field(type, data.copyOfRange(offset, offset + length))
            offset += length
        }
        return fields
    }

    fun epochData(epoch: Long): ByteArray {
        require(epoch in 0..BitchatGroup.MAX_EPOCH)
        return ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(epoch.toInt())
            .array()
    }

    fun epoch(data: ByteArray): Long? {
        if (data.size != Int.SIZE_BYTES) return null
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
    }

    fun timestampData(timestampMs: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(timestampMs)
            .array()

    fun timestamp(data: ByteArray): Long? {
        if (data.size != Long.SIZE_BYTES) return null
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).long
    }

    fun strictUtf8(data: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (_: Exception) {
        null
    }
}

object GroupRosterCoding {
    private const val FINGERPRINT_LENGTH = 32
    private const val SIGNING_KEY_LENGTH = 32
    private const val MAX_NICKNAME_BYTES = 64

    fun encode(members: List<GroupMember>): ByteArray? {
        if (members.size > BitchatGroup.MAX_MEMBERS) return null
        val output = ByteArrayOutputStream()
        output.write(members.size)
        members.forEach { member ->
            val fingerprint = member.fingerprint.dataFromHexString()
            if (fingerprint?.size != FINGERPRINT_LENGTH ||
                member.signingKey.size != SIGNING_KEY_LENGTH
            ) {
                return null
            }
            output.write(fingerprint)
            output.write(member.signingKey)
            val nickname = truncatedNicknameBytes(member.nickname)
            output.write(nickname.size)
            output.write(nickname)
        }
        return output.toByteArray()
    }

    fun decode(data: ByteArray): List<GroupMember>? {
        if (data.isEmpty()) return null
        val count = data[0].toInt() and 0xff
        if (count > BitchatGroup.MAX_MEMBERS) return null
        val members = mutableListOf<GroupMember>()
        var offset = 1
        repeat(count) {
            val fixedLength = FINGERPRINT_LENGTH + SIGNING_KEY_LENGTH + 1
            if (offset + fixedLength > data.size) return null
            val fingerprint =
                data.copyOfRange(offset, offset + FINGERPRINT_LENGTH).hexEncodedString()
            offset += FINGERPRINT_LENGTH
            val signingKey = data.copyOfRange(offset, offset + SIGNING_KEY_LENGTH)
            offset += SIGNING_KEY_LENGTH
            val nicknameLength = data[offset].toInt() and 0xff
            offset += 1
            if (offset + nicknameLength > data.size) return null
            val nickname = GroupTLV.strictUtf8(
                data.copyOfRange(offset, offset + nicknameLength)
            ) ?: return null
            offset += nicknameLength
            members += GroupMember(fingerprint, signingKey, nickname)
        }
        if (offset != data.size) return null
        return members
    }

    private fun truncatedNicknameBytes(nickname: String): ByteArray {
        val output = StringBuilder()
        var offset = 0
        while (offset < nickname.length) {
            val codePoint = nickname.codePointAt(offset)
            val candidate = output.toString() + String(Character.toChars(codePoint))
            if (candidate.toByteArray(Charsets.UTF_8).size > MAX_NICKNAME_BYTES) break
            output.appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
        }
        return output.toString().toByteArray(Charsets.UTF_8)
    }
}

class GroupStatePayload(
    val groupID: ByteArray,
    val name: String,
    val key: ByteArray,
    val epoch: Long,
    val members: List<GroupMember>,
    val creatorFingerprint: String,
    val signature: ByteArray
) {
    fun encode(): ByteArray? {
        val roster = GroupRosterCoding.encode(members) ?: return null
        val creator = creatorFingerprint.dataFromHexString()
        if (creator?.size != 32) return null
        return try {
            GroupTLV.encode(
                FIELD_GROUP_ID to groupID,
                FIELD_NAME to name.toByteArray(Charsets.UTF_8),
                FIELD_KEY to key,
                FIELD_EPOCH to GroupTLV.epochData(epoch),
                FIELD_ROSTER to roster,
                FIELD_CREATOR_FINGERPRINT to creator,
                FIELD_SIGNATURE to signature
            )
        } catch (_: GroupTlvValueTooLongException) {
            null
        }
    }

    fun verifyCreatorSignature(): Boolean {
        if (members.size > BitchatGroup.MAX_MEMBERS) return false
        val creator = members.firstOrNull { it.fingerprint == creatorFingerprint } ?: return false
        val roster = GroupRosterCoding.encode(members) ?: return false
        return GroupCrypto.verify(
            signature,
            signingContent(groupID, epoch, key, roster, name),
            creator.signingKey
        )
    }

    fun asGroup(): BitchatGroup =
        BitchatGroup(groupID, name, epoch, members, creatorFingerprint)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is GroupStatePayload &&
                groupID.contentEquals(other.groupID) &&
                name == other.name &&
                key.contentEquals(other.key) &&
                epoch == other.epoch &&
                members == other.members &&
                creatorFingerprint == other.creatorFingerprint &&
                signature.contentEquals(other.signature))

    override fun hashCode(): Int {
        var result = Arrays.hashCode(groupID)
        result = 31 * result + name.hashCode()
        result = 31 * result + Arrays.hashCode(key)
        result = 31 * result + epoch.hashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + creatorFingerprint.hashCode()
        result = 31 * result + Arrays.hashCode(signature)
        return result
    }

    companion object {
        private const val FIELD_GROUP_ID = 0x01
        private const val FIELD_NAME = 0x02
        private const val FIELD_KEY = 0x03
        private const val FIELD_EPOCH = 0x04
        private const val FIELD_ROSTER = 0x05
        private const val FIELD_CREATOR_FINGERPRINT = 0x06
        private const val FIELD_SIGNATURE = 0x07
        private val SIGNING_DOMAIN = "bitchat-group-v1".toByteArray(Charsets.UTF_8)

        fun signingContent(
            groupID: ByteArray,
            epoch: Long,
            key: ByteArray,
            rosterBlob: ByteArray,
            name: String
        ): ByteArray = concat(
            SIGNING_DOMAIN,
            groupID,
            GroupTLV.epochData(epoch),
            sha256(key),
            sha256(rosterBlob),
            sha256(name.toByteArray(Charsets.UTF_8))
        )

        fun makeSigned(
            group: BitchatGroup,
            key: ByteArray,
            sign: (ByteArray) -> ByteArray?
        ): GroupStatePayload? {
            val roster = GroupRosterCoding.encode(group.members) ?: return null
            val signature = sign(
                signingContent(group.groupID, group.epoch, key, roster, group.name)
            ) ?: return null
            if (signature.size != 64) return null
            return GroupStatePayload(
                group.groupID,
                group.name,
                key,
                group.epoch,
                group.members,
                group.creatorFingerprint,
                signature
            )
        }

        fun decode(data: ByteArray): GroupStatePayload? {
            val fields = GroupTLV.parse(data) ?: return null
            var groupID: ByteArray? = null
            var name: String? = null
            var key: ByteArray? = null
            var epoch: Long? = null
            var members: List<GroupMember>? = null
            var creatorFingerprint: String? = null
            var signature: ByteArray? = null
            fields.forEach { field ->
                when (field.type) {
                    FIELD_GROUP_ID ->
                        if (field.value.size == BitchatGroup.GROUP_ID_LENGTH) groupID = field.value
                    FIELD_NAME -> name = GroupTLV.strictUtf8(field.value)
                    FIELD_KEY ->
                        if (field.value.size == BitchatGroup.KEY_LENGTH) key = field.value
                    FIELD_EPOCH -> epoch = GroupTLV.epoch(field.value)
                    FIELD_ROSTER -> members = GroupRosterCoding.decode(field.value)
                    FIELD_CREATOR_FINGERPRINT ->
                        if (field.value.size == 32) creatorFingerprint = field.value.hexEncodedString()
                    FIELD_SIGNATURE -> if (field.value.size == 64) signature = field.value
                }
            }
            val decodedMembers = members ?: return null
            if (decodedMembers.isEmpty()) return null
            return GroupStatePayload(
                groupID ?: return null,
                name ?: return null,
                key ?: return null,
                epoch ?: return null,
                decodedMembers,
                creatorFingerprint ?: return null,
                signature ?: return null
            )
        }
    }
}

class GroupMessageEnvelope(
    val groupID: ByteArray,
    val epoch: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray
) {
    fun encode(): ByteArray = GroupTLV.encode(
        FIELD_GROUP_ID to groupID,
        FIELD_EPOCH to GroupTLV.epochData(epoch),
        FIELD_NONCE to nonce,
        FIELD_CIPHERTEXT to ciphertext
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is GroupMessageEnvelope &&
                groupID.contentEquals(other.groupID) &&
                epoch == other.epoch &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext))

    override fun hashCode(): Int {
        var result = groupID.contentHashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        private const val FIELD_GROUP_ID = 0x01
        private const val FIELD_EPOCH = 0x02
        private const val FIELD_NONCE = 0x03
        private const val FIELD_CIPHERTEXT = 0x04

        fun decode(data: ByteArray): GroupMessageEnvelope? {
            val fields = GroupTLV.parse(data) ?: return null
            var groupID: ByteArray? = null
            var epoch: Long? = null
            var nonce: ByteArray? = null
            var ciphertext: ByteArray? = null
            fields.forEach { field ->
                when (field.type) {
                    FIELD_GROUP_ID ->
                        if (field.value.size == BitchatGroup.GROUP_ID_LENGTH) groupID = field.value
                    FIELD_EPOCH -> epoch = GroupTLV.epoch(field.value)
                    FIELD_NONCE -> if (field.value.size == 12) nonce = field.value
                    FIELD_CIPHERTEXT -> if (field.value.isNotEmpty()) ciphertext = field.value
                }
            }
            return GroupMessageEnvelope(
                groupID ?: return null,
                epoch ?: return null,
                nonce ?: return null,
                ciphertext ?: return null
            )
        }
    }
}

data class GroupMessagePlaintext(
    val messageID: String,
    val senderSigningKey: ByteArray,
    val senderNickname: String,
    val timestampMs: Long,
    val content: String
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is GroupMessagePlaintext &&
                messageID == other.messageID &&
                senderSigningKey.contentEquals(other.senderSigningKey) &&
                senderNickname == other.senderNickname &&
                timestampMs == other.timestampMs &&
                content == other.content)

    override fun hashCode(): Int {
        var result = messageID.hashCode()
        result = 31 * result + senderSigningKey.contentHashCode()
        result = 31 * result + senderNickname.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + content.hashCode()
        return result
    }
}

sealed class GroupCryptoException(message: String) : Exception(message) {
    class MalformedPayload : GroupCryptoException("malformed group payload")
    class SigningFailed : GroupCryptoException("group message signing failed")
    class SealFailed : GroupCryptoException("group message sealing failed")
    class DecryptionFailed : GroupCryptoException("group message decryption failed")
    class BadSenderSignature : GroupCryptoException("bad group sender signature")
}

object GroupCrypto {
    private const val FIELD_MESSAGE_ID = 0x01
    private const val FIELD_SENDER_SIGNING_KEY = 0x02
    private const val FIELD_SENDER_NICKNAME = 0x03
    private const val FIELD_TIMESTAMP = 0x04
    private const val FIELD_CONTENT = 0x05
    private const val FIELD_SIGNATURE = 0x06
    private val MESSAGE_SIGNING_DOMAIN =
        "bitchat-group-msg-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun messageSigningContent(
        groupID: ByteArray,
        epoch: Long,
        messageID: String,
        timestampMs: Long,
        content: String
    ): ByteArray = concat(
        MESSAGE_SIGNING_DOMAIN,
        groupID,
        GroupTLV.epochData(epoch),
        messageID.toByteArray(Charsets.UTF_8),
        GroupTLV.timestampData(timestampMs),
        content.toByteArray(Charsets.UTF_8)
    )

    fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    @Throws(GroupCryptoException::class, GroupTlvValueTooLongException::class)
    fun sealMessage(
        content: String,
        messageID: String,
        senderNickname: String,
        senderSigningKey: ByteArray,
        timestampMs: Long,
        groupID: ByteArray,
        epoch: Long,
        key: ByteArray,
        sign: (ByteArray) -> ByteArray?
    ): ByteArray {
        if (!isCanonicalMessageID(messageID)) {
            throw GroupCryptoException.MalformedPayload()
        }
        val signature = sign(
            messageSigningContent(groupID, epoch, messageID, timestampMs, content)
        )
        if (signature?.size != 64) throw GroupCryptoException.SigningFailed()

        val inner = GroupTLV.encode(
            FIELD_MESSAGE_ID to messageID.toByteArray(Charsets.UTF_8),
            FIELD_SENDER_SIGNING_KEY to senderSigningKey,
            FIELD_SENDER_NICKNAME to senderNickname.toByteArray(Charsets.UTF_8),
            FIELD_TIMESTAMP to GroupTLV.timestampData(timestampMs),
            FIELD_CONTENT to content.toByteArray(Charsets.UTF_8),
            FIELD_SIGNATURE to signature
        )
        if (key.size != BitchatGroup.KEY_LENGTH ||
            groupID.size != BitchatGroup.GROUP_ID_LENGTH
        ) {
            throw GroupCryptoException.SealFailed()
        }

        return try {
            val nonce = ByteArray(12).also(random::nextBytes)
            val aad = concat(groupID, GroupTLV.epochData(epoch))
            val ciphertext = crypt(encrypt = true, key, nonce, aad, inner)
            GroupMessageEnvelope(groupID, epoch, nonce, ciphertext).encode()
        } catch (error: GroupTlvValueTooLongException) {
            throw error
        } catch (_: Exception) {
            throw GroupCryptoException.SealFailed()
        }
    }

    @Throws(GroupCryptoException::class)
    fun openMessage(envelope: GroupMessageEnvelope, key: ByteArray): GroupMessagePlaintext {
        if (key.size != BitchatGroup.KEY_LENGTH || envelope.ciphertext.size <= 16) {
            throw GroupCryptoException.DecryptionFailed()
        }
        val inner = try {
            crypt(
                encrypt = false,
                key,
                envelope.nonce,
                concat(envelope.groupID, GroupTLV.epochData(envelope.epoch)),
                envelope.ciphertext
            )
        } catch (_: Exception) {
            throw GroupCryptoException.DecryptionFailed()
        }

        val fields = GroupTLV.parse(inner) ?: throw GroupCryptoException.MalformedPayload()
        var messageID: String? = null
        var senderSigningKey: ByteArray? = null
        var senderNickname: String? = null
        var timestampMs: Long? = null
        var content: String? = null
        var signature: ByteArray? = null
        fields.forEach { field ->
            when (field.type) {
                FIELD_MESSAGE_ID -> messageID = GroupTLV.strictUtf8(field.value)
                FIELD_SENDER_SIGNING_KEY ->
                    if (field.value.size == 32) senderSigningKey = field.value
                FIELD_SENDER_NICKNAME -> senderNickname = GroupTLV.strictUtf8(field.value)
                FIELD_TIMESTAMP -> timestampMs = GroupTLV.timestamp(field.value)
                FIELD_CONTENT -> content = GroupTLV.strictUtf8(field.value)
                FIELD_SIGNATURE -> if (field.value.size == 64) signature = field.value
            }
        }
        val decodedMessageID = messageID?.takeIf(::isCanonicalMessageID)
            ?: throw GroupCryptoException.MalformedPayload()
        val decodedSigningKey = senderSigningKey ?: throw GroupCryptoException.MalformedPayload()
        val decodedNickname = senderNickname ?: throw GroupCryptoException.MalformedPayload()
        val decodedTimestamp = timestampMs ?: throw GroupCryptoException.MalformedPayload()
        val decodedContent = content ?: throw GroupCryptoException.MalformedPayload()
        val decodedSignature = signature ?: throw GroupCryptoException.MalformedPayload()

        val signingContent = messageSigningContent(
            envelope.groupID,
            envelope.epoch,
            decodedMessageID,
            decodedTimestamp,
            decodedContent
        )
        if (!verify(decodedSignature, signingContent, decodedSigningKey)) {
            throw GroupCryptoException.BadSenderSignature()
        }
        return GroupMessagePlaintext(
            decodedMessageID,
            decodedSigningKey,
            decodedNickname,
            decodedTimestamp,
            decodedContent
        )
    }

    private fun isCanonicalMessageID(messageID: String): Boolean {
        val encoded = messageID.toByteArray(Charsets.UTF_8)
        if (encoded.size != UUID_TEXT_LENGTH || !UUID_PATTERN.matches(messageID)) return false
        return try {
            UUID.fromString(messageID)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    @Throws(InvalidCipherTextException::class)
    private fun crypt(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(input.size))
        var length = cipher.processBytes(input, 0, input.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }

    private const val UUID_TEXT_LENGTH = 36
    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
}

internal fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

internal fun concat(vararg arrays: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(arrays.sumOf(ByteArray::size))
    arrays.forEach(output::write)
    return output.toByteArray()
}
