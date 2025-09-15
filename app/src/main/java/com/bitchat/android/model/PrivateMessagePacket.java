package com.bitchat.android.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Paquet de message privé avec encodage TLV.
 * Correspond exactement au PrivateMessagePacket sur iOS.
 */
public final class PrivateMessagePacket implements Parcelable {

    private final String messageID;
    private final String content;

    private enum TLVType {
        MESSAGE_ID((byte) 0x00),
        CONTENT((byte) 0x01);

        private final byte value;
        private static final Map<Byte, TLVType> map = new HashMap<>();
        TLVType(byte value) { this.value = value; }
        static { for (TLVType type : TLVType.values()) { map.put(type.value, type); } }
        public static TLVType fromValue(byte value) { return map.get(value); }
    }

    public PrivateMessagePacket(String messageID, String content) {
        this.messageID = messageID;
        this.content = content;
    }

    // Getters
    public String getMessageID() { return messageID; }
    public String getContent() { return content; }

    /**
     * Encode en données binaires TLV.
     */
    public byte[] encode() {
        byte[] messageIDData = messageID.getBytes(StandardCharsets.UTF_8);
        byte[] contentData = content.getBytes(StandardCharsets.UTF_8);

        if (messageIDData.length > 255 || contentData.length > 255) {
            return null;
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            buffer.write(TLVType.MESSAGE_ID.value);
            buffer.write((byte) messageIDData.length);
            buffer.write(messageIDData);

            buffer.write(TLVType.CONTENT.value);
            buffer.write((byte) contentData.length);
            buffer.write(contentData);
        } catch (IOException e) {
            return null; // Ne devrait jamais arriver
        }
        return buffer.toByteArray();
    }

    /**
     * Décode à partir de données binaires TLV.
     */
    public static PrivateMessagePacket decode(byte[] data) {
        int offset = 0;
        String messageID = null;
        String content = null;

        while (offset + 2 <= data.length) {
            byte typeValue = data[offset++];
            TLVType type = TLVType.fromValue(typeValue);

            // Le code Kotlin original retournait null si le type était inconnu.
            // Pour être tolérant aux futures versions, on pourrait le skipper.
            // Je reste fidèle à l'implémentation originale.
            if (type == null) return null;

            int length = data[offset++] & 0xFF;
            if (offset + length > data.length) return null;

            byte[] value = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;

            switch (type) {
                case MESSAGE_ID:
                    messageID = new String(value, StandardCharsets.UTF_8);
                    break;
                case CONTENT:
                    content = new String(value, StandardCharsets.UTF_8);
                    break;
            }
        }

        if (messageID != null && content != null) {
            return new PrivateMessagePacket(messageID, content);
        } else {
            return null;
        }
    }

    // --- Implémentation de Parcelable ---

    protected PrivateMessagePacket(Parcel in) {
        messageID = in.readString();
        content = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(messageID);
        dest.writeString(content);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<PrivateMessagePacket> CREATOR = new Creator<PrivateMessagePacket>() {
        @Override
        public PrivateMessagePacket createFromParcel(Parcel in) {
            return new PrivateMessagePacket(in);
        }
        @Override
        public PrivateMessagePacket[] newArray(int size) {
            return new PrivateMessagePacket[size];
        }
    };

    @Override
    public String toString() {
        String shortContent = content.length() > 50 ? content.substring(0, 50) + "..." : content;
        return "PrivateMessagePacket(messageID='" + messageID + "', content='" + shortContent + "')";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrivateMessagePacket that = (PrivateMessagePacket) o;
        return Objects.equals(messageID, that.messageID) && Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageID, content);
    }
}
