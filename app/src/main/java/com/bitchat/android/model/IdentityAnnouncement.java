package com.bitchat.android.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.bitchat.android.util.BinaryEncodingUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Représente une annonce d'identité, utilisant un encodage TLV (Type-Length-Value).
 * Compatible avec le format TLV du paquet d'annonce d'iOS.
 */
public final class IdentityAnnouncement implements Parcelable {

    private final String nickname;
    private final byte[] noisePublicKey;
    private final byte[] signingPublicKey;

    /**
     * Types TLV correspondant à l'implémentation iOS.
     */
    private enum TLVType {
        NICKNAME((byte) 0x01),
        NOISE_PUBLIC_KEY((byte) 0x02),
        SIGNING_PUBLIC_KEY((byte) 0x03);

        private final byte value;
        private static final Map<Byte, TLVType> map = new HashMap<>();

        TLVType(byte value) { this.value = value; }

        static {
            for (TLVType type : TLVType.values()) {
                map.put(type.value, type);
            }
        }

        public static TLVType fromValue(byte value) {
            return map.get(value);
        }
    }

    public IdentityAnnouncement(String nickname, byte[] noisePublicKey, byte[] signingPublicKey) {
        this.nickname = nickname;
        this.noisePublicKey = noisePublicKey;
        this.signingPublicKey = signingPublicKey;
    }

    // Getters
    public String getNickname() { return nickname; }
    public byte[] getNoisePublicKey() { return noisePublicKey; }
    public byte[] getSigningPublicKey() { return signingPublicKey; }

    /**
     * Encode l'objet en données binaires TLV.
     * @return Le tableau de bytes encodé, ou null si une des valeurs est trop grande.
     */
    public byte[] encode() {
        byte[] nicknameData = nickname.getBytes(StandardCharsets.UTF_8);
        if (nicknameData.length > 255 || noisePublicKey.length > 255 || signingPublicKey.length > 255) {
            return null;
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // TLV pour le pseudonyme
            buffer.write(TLVType.NICKNAME.value);
            buffer.write((byte) nicknameData.length);
            buffer.write(nicknameData);

            // TLV pour la clé publique Noise
            buffer.write(TLVType.NOISE_PUBLIC_KEY.value);
            buffer.write((byte) noisePublicKey.length);
            buffer.write(noisePublicKey);

            // TLV pour la clé publique de signature
            buffer.write(TLVType.SIGNING_PUBLIC_KEY.value);
            buffer.write((byte) signingPublicKey.length);
            buffer.write(signingPublicKey);
        } catch (IOException e) {
            // Ne devrait jamais arriver avec ByteArrayOutputStream
            return null;
        }
        return buffer.toByteArray();
    }

    /**
     * Décode des données binaires TLV pour créer un objet IdentityAnnouncement.
     * @param data Les données à décoder.
     * @return Un objet IdentityAnnouncement, ou null si les données sont invalides.
     */
    public static IdentityAnnouncement decode(byte[] data) {
        int offset = 0;
        String nickname = null;
        byte[] noisePublicKey = null;
        byte[] signingPublicKey = null;

        while (offset + 2 <= data.length) {
            byte typeValue = data[offset++];
            TLVType type = TLVType.fromValue(typeValue);

            int length = data[offset++] & 0xFF;

            if (offset + length > data.length) return null;

            byte[] value = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;

            if (type != null) {
                switch (type) {
                    case NICKNAME:
                        nickname = new String(value, StandardCharsets.UTF_8);
                        break;
                    case NOISE_PUBLIC_KEY:
                        noisePublicKey = value;
                        break;
                    case SIGNING_PUBLIC_KEY:
                        signingPublicKey = value;
                        break;
                }
            }
        }

        if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
            return new IdentityAnnouncement(nickname, noisePublicKey, signingPublicKey);
        } else {
            return null;
        }
    }

    // --- Implémentation de Parcelable ---

    protected IdentityAnnouncement(Parcel in) {
        nickname = in.readString();
        noisePublicKey = in.createByteArray();
        signingPublicKey = in.createByteArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(nickname);
        dest.writeByteArray(noisePublicKey);
        dest.writeByteArray(signingPublicKey);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<IdentityAnnouncement> CREATOR = new Creator<IdentityAnnouncement>() {
        @Override
        public IdentityAnnouncement createFromParcel(Parcel in) {
            return new IdentityAnnouncement(in);
        }
        @Override
        public IdentityAnnouncement[] newArray(int size) {
            return new IdentityAnnouncement[size];
        }
    };

    // --- equals, hashCode, toString ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdentityAnnouncement that = (IdentityAnnouncement) o;
        return Objects.equals(nickname, that.nickname) &&
               Arrays.equals(noisePublicKey, that.noisePublicKey) &&
               Arrays.equals(signingPublicKey, that.signingPublicKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(nickname);
        result = 31 * result + Arrays.hashCode(noisePublicKey);
        result = 31 * result + Arrays.hashCode(signingPublicKey);
        return result;
    }

    @Override
    public String toString() {
        return "IdentityAnnouncement(nickname='" + nickname + '\'' +
               ", noisePublicKey=" + BinaryEncodingUtils.hexEncodedString(noisePublicKey).substring(0, 16) + "..." +
               ", signingPublicKey=" + BinaryEncodingUtils.hexEncodedString(signingPublicKey).substring(0, 16) + "...)";
    }
}
