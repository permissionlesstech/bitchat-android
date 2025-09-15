package com.bitchat.android.protocol;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Représente un paquet binaire, 100% compatible avec la version iOS.
 */
public final class BitchatPacket implements Parcelable {

    private final byte version;
    private final byte type;
    private final byte[] senderID;
    private final byte[] recipientID;
    private final long timestamp;
    private final byte[] payload;
    private byte[] signature;
    private byte ttl;

    public BitchatPacket(byte version, byte type, byte[] senderID, byte[] recipientID, long timestamp, byte[] payload, byte[] signature, byte ttl) {
        this.version = version;
        this.type = type;
        this.senderID = senderID;
        this.recipientID = recipientID;
        this.timestamp = timestamp;
        this.payload = payload;
        this.signature = signature;
        this.ttl = ttl;
    }

    /**
     * Constructeur auxiliaire pour créer facilement un paquet sortant.
     */
    public BitchatPacket(byte type, byte ttl, String senderID, byte[] payload) {
        this.version = 1;
        this.type = type;
        this.senderID = hexStringToByteArray(senderID);
        this.recipientID = null;
        this.timestamp = System.currentTimeMillis();
        this.payload = payload;
        this.signature = null;
        this.ttl = ttl;
    }

    // Getters
    public byte getVersion() { return version; }
    public byte getType() { return type; }
    public byte[] getSenderID() { return senderID; }
    public byte[] getRecipientID() { return recipientID; }
    public long getTimestamp() { return timestamp; }
    public byte[] getPayload() { return payload; }
    public byte[] getSignature() { return signature; }
    public byte getTtl() { return ttl; }

    // Setters for mutable fields
    public void setSignature(byte[] signature) { this.signature = signature; }
    public void setTtl(byte ttl) { this.ttl = ttl; }

    /**
     * Sérialise le paquet complet en données binaires.
     * @return Le tableau de bytes encodé.
     */
    public byte[] toBinaryData() {
        return BinaryProtocol.encode(this);
    }

    /**
     * Crée une représentation binaire pour la signature (exclut la signature et le TTL).
     * @return Le tableau de bytes à signer.
     */
    public byte[] toBinaryDataForSigning() {
        // Le TTL est exclu car il change à chaque saut. La signature est exclue par définition.
        BitchatPacket unsignedPacket = new BitchatPacket(
            this.version, this.type, this.senderID, this.recipientID,
            this.timestamp, this.payload, null, (byte) 0
        );
        return BinaryProtocol.encode(unsignedPacket);
    }

    private static byte[] hexStringToByteArray(String hexString) {
        byte[] result = new byte[8]; // Toujours 8 bytes
        String tempID = hexString.replaceAll("[^0-9a-fA-F]", "");
        int len = tempID.length();
        for (int i = 0; i < 8; i++) {
            if (i * 2 < len) {
                String hexByte = tempID.substring(i * 2, i * 2 + 2);
                result[i] = (byte) Integer.parseInt(hexByte, 16);
            } else {
                result[i] = 0;
            }
        }
        return result;
    }

    // --- Implémentation de Parcelable ---

    protected BitchatPacket(Parcel in) {
        version = in.readByte();
        type = in.readByte();
        senderID = in.createByteArray();
        recipientID = in.createByteArray();
        timestamp = in.readLong();
        payload = in.createByteArray();
        signature = in.createByteArray();
        ttl = in.readByte();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(version);
        dest.writeByte(type);
        dest.writeByteArray(senderID);
        dest.writeByteArray(recipientID);
        dest.writeLong(timestamp);
        dest.writeByteArray(payload);
        dest.writeByteArray(signature);
        dest.writeByte(ttl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BitchatPacket> CREATOR = new Creator<BitchatPacket>() {
        @Override
        public BitchatPacket createFromParcel(Parcel in) {
            return new BitchatPacket(in);
        }
        @Override
        public BitchatPacket[] newArray(int size) {
            return new BitchatPacket[size];
        }
    };

    // --- equals et hashCode ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BitchatPacket that = (BitchatPacket) o;
        return version == that.version && type == that.type && timestamp == that.timestamp && ttl == that.ttl &&
               Arrays.equals(senderID, that.senderID) &&
               Arrays.equals(recipientID, that.recipientID) &&
               Arrays.equals(payload, that.payload) &&
               Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(version, type, timestamp, ttl);
        result = 31 * result + Arrays.hashCode(senderID);
        result = 31 * result + Arrays.hashCode(recipientID);
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Arrays.hashCode(signature);
        return result;
    }
}
