package com.bitchat.android.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Représente un message Bitchat.
 * Cette classe est conçue pour être 100% compatible avec la version iOS.
 * Elle implémente Parcelable pour pouvoir être passée entre les composants Android.
 */
public final class BitchatMessage implements Parcelable {

    private final String id;
    private final String sender;
    private final String content;
    private final Date timestamp;
    private final boolean isRelay;
    private final String originalSender;
    private final boolean isPrivate;
    private final String recipientNickname;
    private final String senderPeerID;
    private final List<String> mentions;
    private final String channel;
    private final byte[] encryptedContent;
    private final boolean isEncrypted;
    private final DeliveryStatus deliveryStatus;
    private final Integer powDifficulty;

    public BitchatMessage(String id, String sender, String content, Date timestamp, boolean isRelay, String originalSender, boolean isPrivate, String recipientNickname, String senderPeerID, List<String> mentions, String channel, byte[] encryptedContent, boolean isEncrypted, DeliveryStatus deliveryStatus, Integer powDifficulty) {
        this.id = id;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
        this.isRelay = isRelay;
        this.originalSender = originalSender;
        this.isPrivate = isPrivate;
        this.recipientNickname = recipientNickname;
        this.senderPeerID = senderPeerID;
        this.mentions = mentions;
        this.channel = channel;
        this.encryptedContent = encryptedContent;
        this.isEncrypted = isEncrypted;
        this.deliveryStatus = deliveryStatus;
        this.powDifficulty = powDifficulty;
    }

    /**
     * Constructeur de copie pour mettre à jour un champ de manière immuable (simule data class.copy()).
     * @param other Le message original à copier.
     * @param newDeliveryStatus Le nouveau statut de livraison à appliquer.
     */
    public BitchatMessage(BitchatMessage other, DeliveryStatus newDeliveryStatus) {
        this.id = other.id;
        this.sender = other.sender;
        this.content = other.content;
        this.timestamp = other.timestamp;
        this.isRelay = other.isRelay;
        this.originalSender = other.originalSender;
        this.isPrivate = other.isPrivate;
        this.recipientNickname = other.recipientNickname;
        this.senderPeerID = other.senderPeerID;
        this.mentions = other.mentions;
        this.channel = other.channel;
        this.encryptedContent = other.encryptedContent;
        this.isEncrypted = other.isEncrypted;
        this.deliveryStatus = newDeliveryStatus; // Le champ mis à jour
        this.powDifficulty = other.powDifficulty;
    }

    // Getters
    public String getId() { return id; }
    public String getSender() { return sender; }
    public String getContent() { return content; }
    public Date getTimestamp() { return timestamp; }
    public boolean isRelay() { return isRelay; }
    public String getOriginalSender() { return originalSender; }
    public boolean isPrivate() { return isPrivate; }
    public String getRecipientNickname() { return recipientNickname; }
    public String getSenderPeerID() { return senderPeerID; }
    public List<String> getMentions() { return mentions; }
    public String getChannel() { return channel; }
    public byte[] getEncryptedContent() { return encryptedContent; }
    public boolean isEncrypted() { return isEncrypted; }
    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public Integer getPowDifficulty() { return powDifficulty; }

    // --- Logique de sérialisation binaire ---

    /**
     * Convertit le message en charge utile binaire, compatible avec iOS.
     * @return un tableau de bytes représentant le message.
     */
    public byte[] toBinaryPayload() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);

            int flags = 0;
            if (isRelay) flags |= 0x01;
            if (isPrivate) flags |= 0x02;
            if (originalSender != null) flags |= 0x04;
            if (recipientNickname != null) flags |= 0x08;
            if (senderPeerID != null) flags |= 0x10;
            if (mentions != null && !mentions.isEmpty()) flags |= 0x20;
            if (channel != null) flags |= 0x40;
            if (isEncrypted) flags |= 0x80;
            buffer.put((byte) flags);

            buffer.putLong(timestamp.getTime());

            byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
            buffer.put((byte) Math.min(idBytes.length, 255));
            buffer.put(idBytes, 0, Math.min(idBytes.length, 255));

            byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
            buffer.put((byte) Math.min(senderBytes.length, 255));
            buffer.put(senderBytes, 0, Math.min(senderBytes.length, 255));

            if (isEncrypted && encryptedContent != null) {
                int length = Math.min(encryptedContent.length, 65535);
                buffer.putShort((short) length);
                buffer.put(encryptedContent, 0, length);
            } else {
                byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
                int length = Math.min(contentBytes.length, 65535);
                buffer.putShort((short) length);
                buffer.put(contentBytes, 0, length);
            }

            if (originalSender != null) {
                byte[] origBytes = originalSender.getBytes(StandardCharsets.UTF_8);
                buffer.put((byte) Math.min(origBytes.length, 255));
                buffer.put(origBytes, 0, Math.min(origBytes.length, 255));
            }
            if (recipientNickname != null) {
                byte[] recipBytes = recipientNickname.getBytes(StandardCharsets.UTF_8);
                buffer.put((byte) Math.min(recipBytes.length, 255));
                buffer.put(recipBytes, 0, Math.min(recipBytes.length, 255));
            }
            if (senderPeerID != null) {
                byte[] peerBytes = senderPeerID.getBytes(StandardCharsets.UTF_8);
                buffer.put((byte) Math.min(peerBytes.length, 255));
                buffer.put(peerBytes, 0, Math.min(peerBytes.length, 255));
            }
            if (mentions != null) {
                buffer.put((byte) Math.min(mentions.size(), 255));
                for (int i = 0; i < Math.min(mentions.size(), 255); i++) {
                    byte[] mentionBytes = mentions.get(i).getBytes(StandardCharsets.UTF_8);
                    buffer.put((byte) Math.min(mentionBytes.length, 255));
                    buffer.put(mentionBytes, 0, Math.min(mentionBytes.length, 255));
                }
            }
            if (channel != null) {
                byte[] channelBytes = channel.getBytes(StandardCharsets.UTF_8);
                buffer.put((byte) Math.min(channelBytes.length, 255));
                buffer.put(channelBytes, 0, Math.min(channelBytes.length, 255));
            }

            byte[] result = new byte[buffer.position()];
            buffer.rewind();
            buffer.get(result);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Crée un BitchatMessage à partir d'une charge utile binaire.
     * @param data Le tableau de bytes à désérialiser.
     * @return Un objet BitchatMessage, ou null en cas d'erreur.
     */
    public static BitchatMessage fromBinaryPayload(byte[] data) {
        try {
            if (data.length < 13) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            int flags = buffer.get() & 0xFF;
            boolean isRelay = (flags & 0x01) != 0;
            boolean isPrivate = (flags & 0x02) != 0;
            boolean hasOriginalSender = (flags & 0x04) != 0;
            boolean hasRecipientNickname = (flags & 0x08) != 0;
            boolean hasSenderPeerID = (flags & 0x10) != 0;
            boolean hasMentions = (flags & 0x20) != 0;
            boolean hasChannel = (flags & 0x40) != 0;
            boolean isEncrypted = (flags & 0x80) != 0;

            Date timestamp = new Date(buffer.getLong());

            int idLength = buffer.get() & 0xFF;
            if (buffer.remaining() < idLength) return null;
            byte[] idBytes = new byte[idLength];
            buffer.get(idBytes);
            String id = new String(idBytes, StandardCharsets.UTF_8);

            int senderLength = buffer.get() & 0xFF;
            if (buffer.remaining() < senderLength) return null;
            byte[] senderBytes = new byte[senderLength];
            buffer.get(senderBytes);
            String sender = new String(senderBytes, StandardCharsets.UTF_8);

            int contentLength = buffer.getShort() & 0xFFFF;
            if (buffer.remaining() < contentLength) return null;
            String content;
            byte[] encryptedContent;
            if (isEncrypted) {
                encryptedContent = new byte[contentLength];
                buffer.get(encryptedContent);
                content = "";
            } else {
                byte[] contentBytes = new byte[contentLength];
                buffer.get(contentBytes);
                content = new String(contentBytes, StandardCharsets.UTF_8);
                encryptedContent = null;
            }

            String originalSender = null;
            if (hasOriginalSender && buffer.hasRemaining()) {
                int length = buffer.get() & 0xFF;
                if (buffer.remaining() >= length) {
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    originalSender = new String(bytes, StandardCharsets.UTF_8);
                }
            }

            String recipientNickname = null;
            if (hasRecipientNickname && buffer.hasRemaining()) {
                int length = buffer.get() & 0xFF;
                if (buffer.remaining() >= length) {
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    recipientNickname = new String(bytes, StandardCharsets.UTF_8);
                }
            }

            String senderPeerID = null;
            if (hasSenderPeerID && buffer.hasRemaining()) {
                int length = buffer.get() & 0xFF;
                if (buffer.remaining() >= length) {
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    senderPeerID = new String(bytes, StandardCharsets.UTF_8);
                }
            }

            List<String> mentions = null;
            if (hasMentions && buffer.hasRemaining()) {
                int count = buffer.get() & 0xFF;
                mentions = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    if (buffer.hasRemaining()) {
                        int length = buffer.get() & 0xFF;
                        if (buffer.remaining() >= length) {
                            byte[] bytes = new byte[length];
                            buffer.get(bytes);
                            mentions.add(new String(bytes, StandardCharsets.UTF_8));
                        }
                    }
                }
            }

            String channel = null;
            if (hasChannel && buffer.hasRemaining()) {
                int length = buffer.get() & 0xFF;
                if (buffer.remaining() >= length) {
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    channel = new String(bytes, StandardCharsets.UTF_8);
                }
            }

            return new BitchatMessage(id, sender, content, timestamp, isRelay, originalSender, isPrivate, recipientNickname, senderPeerID, mentions, channel, encryptedContent, isEncrypted, null, null);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Implémentation de Parcelable ---

    protected BitchatMessage(Parcel in) {
        id = in.readString();
        sender = in.readString();
        content = in.readString();
        timestamp = new Date(in.readLong());
        isRelay = in.readByte() != 0;
        originalSender = in.readString();
        isPrivate = in.readByte() != 0;
        recipientNickname = in.readString();
        senderPeerID = in.readString();
        mentions = in.createStringArrayList();
        channel = in.readString();
        encryptedContent = in.createByteArray();
        isEncrypted = in.readByte() != 0;
        if (in.readByte() == 1) {
            powDifficulty = in.readInt();
        } else {
            powDifficulty = null;
        }

        // Lecture de DeliveryStatus
        if (in.readByte() == 1) {
            int type = in.readInt();
            switch(type) {
                case 0: deliveryStatus = DeliveryStatus.Sending.CREATOR.createFromParcel(in); break;
                case 1: deliveryStatus = DeliveryStatus.Sent.CREATOR.createFromParcel(in); break;
                case 2: deliveryStatus = DeliveryStatus.Delivered.CREATOR.createFromParcel(in); break;
                case 3: deliveryStatus = DeliveryStatus.Read.CREATOR.createFromParcel(in); break;
                case 4: deliveryStatus = DeliveryStatus.Failed.CREATOR.createFromParcel(in); break;
                case 5: deliveryStatus = DeliveryStatus.PartiallyDelivered.CREATOR.createFromParcel(in); break;
                default: deliveryStatus = null;
            }
        } else {
            deliveryStatus = null;
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(sender);
        dest.writeString(content);
        dest.writeLong(timestamp.getTime());
        dest.writeByte((byte) (isRelay ? 1 : 0));
        dest.writeString(originalSender);
        dest.writeByte((byte) (isPrivate ? 1 : 0));
        dest.writeString(recipientNickname);
        dest.writeString(senderPeerID);
        dest.writeStringList(mentions);
        dest.writeString(channel);
        dest.writeByteArray(encryptedContent);
        dest.writeByte((byte) (isEncrypted ? 1 : 0));
        if (powDifficulty != null) {
            dest.writeByte((byte) 1);
            dest.writeInt(powDifficulty);
        } else {
            dest.writeByte((byte) 0);
        }

        // Ecriture de DeliveryStatus avec un identifiant de type
        if (deliveryStatus != null) {
            dest.writeByte((byte) 1);
            if (deliveryStatus instanceof DeliveryStatus.Sending) {
                dest.writeInt(0);
                ((DeliveryStatus.Sending) deliveryStatus).writeToParcel(dest, flags);
            } else if (deliveryStatus instanceof DeliveryStatus.Sent) {
                dest.writeInt(1);
                ((DeliveryStatus.Sent) deliveryStatus).writeToParcel(dest, flags);
            } else if (deliveryStatus instanceof DeliveryStatus.Delivered) {
                dest.writeInt(2);
                ((DeliveryStatus.Delivered) deliveryStatus).writeToParcel(dest, flags);
            } else if (deliveryStatus instanceof DeliveryStatus.Read) {
                dest.writeInt(3);
                ((DeliveryStatus.Read) deliveryStatus).writeToParcel(dest, flags);
            } else if (deliveryStatus instanceof DeliveryStatus.Failed) {
                dest.writeInt(4);
                ((DeliveryStatus.Failed) deliveryStatus).writeToParcel(dest, flags);
            } else if (deliveryStatus instanceof DeliveryStatus.PartiallyDelivered) {
                dest.writeInt(5);
                ((DeliveryStatus.PartiallyDelivered) deliveryStatus).writeToParcel(dest, flags);
            }
        } else {
            dest.writeByte((byte) 0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BitchatMessage> CREATOR = new Creator<BitchatMessage>() {
        @Override
        public BitchatMessage createFromParcel(Parcel in) {
            return new BitchatMessage(in);
        }

        @Override
        public BitchatMessage[] newArray(int size) {
            return new BitchatMessage[size];
        }
    };

    // --- equals et hashCode ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BitchatMessage that = (BitchatMessage) o;
        return isRelay == that.isRelay && isPrivate == that.isPrivate && isEncrypted == that.isEncrypted && Objects.equals(id, that.id) && Objects.equals(sender, that.sender) && Objects.equals(content, that.content) && Objects.equals(timestamp, that.timestamp) && Objects.equals(originalSender, that.originalSender) && Objects.equals(recipientNickname, that.recipientNickname) && Objects.equals(senderPeerID, that.senderPeerID) && Objects.equals(mentions, that.mentions) && Objects.equals(channel, that.channel) && Arrays.equals(encryptedContent, that.encryptedContent) && Objects.equals(deliveryStatus, that.deliveryStatus) && Objects.equals(powDifficulty, that.powDifficulty);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, sender, content, timestamp, isRelay, originalSender, isPrivate, recipientNickname, senderPeerID, mentions, channel, isEncrypted, deliveryStatus, powDifficulty);
        result = 31 * result + Arrays.hashCode(encryptedContent);
        return result;
    }
}
