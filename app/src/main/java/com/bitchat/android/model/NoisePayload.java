package com.bitchat.android.model;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Classe d'aide pour créer et analyser les charges utiles Noise.
 * Correspond à l'aide NoisePayload sur iOS.
 */
public final class NoisePayload implements Parcelable {

    private final NoisePayloadType type;
    private final byte[] data;

    public NoisePayload(NoisePayloadType type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    // Getters
    public NoisePayloadType getType() { return type; }
    public byte[] getData() { return data; }

    /**
     * Encode la charge utile avec un préfixe de type.
     * Format: [octet_de_type][donnees_charge_utile]
     * @return Le tableau de bytes encodé.
     */
    public byte[] encode() {
        byte[] result = new byte[1 + data.length];
        result[0] = type.getValue();
        System.arraycopy(data, 0, result, 1, data.length);
        return result;
    }

    /**
     * Décode la charge utile à partir de données.
     * @param data Les données à décoder.
     * @return Un objet NoisePayload, ou null si les données sont invalides.
     */
    public static NoisePayload decode(byte[] data) {
        if (data == null || data.length == 0) return null;

        int typeValue = data[0] & 0xFF;
        NoisePayloadType type = NoisePayloadType.fromValue(typeValue);
        if (type == null) return null;

        byte[] payloadData;
        if (data.length > 1) {
            payloadData = Arrays.copyOfRange(data, 1, data.length);
        } else {
            payloadData = new byte[0];
        }

        return new NoisePayload(type, payloadData);
    }

    // --- Implémentation de Parcelable ---

    protected NoisePayload(Parcel in) {
        type = NoisePayloadType.values()[in.readInt()];
        data = in.createByteArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type.ordinal());
        dest.writeByteArray(data);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NoisePayload> CREATOR = new Creator<NoisePayload>() {
        @Override
        public NoisePayload createFromParcel(Parcel in) {
            return new NoisePayload(in);
        }
        @Override
        public NoisePayload[] newArray(int size) {
            return new NoisePayload[size];
        }
    };

    // --- equals et hashCode ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NoisePayload that = (NoisePayload) o;
        return type == that.type && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}
