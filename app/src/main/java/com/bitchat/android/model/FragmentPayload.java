package com.bitchat.android.model;

import com.bitchat.android.util.BinaryEncodingUtils;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Représente la charge utile d'un fragment de message, 100% compatible avec iOS.
 * Gère l'encodage et le décodage des fragments.
 * <p>
 * Structure de la charge utile :
 * - 8 bytes: ID du fragment (aléatoire)
 * - 2 bytes: Index (big-endian)
 * - 2 bytes: Nombre total de fragments (big-endian)
 * - 1 byte: Type de message original
 * - Variable: Données du fragment
 * <p>
 * Taille totale de l'en-tête : 13 bytes
 */
public final class FragmentPayload {

    public static final int HEADER_SIZE = 13;
    public static final int FRAGMENT_ID_SIZE = 8;

    private final byte[] fragmentID;      // 8 bytes
    private final int index;              // Index du fragment (base 0)
    private final int total;              // Nombre total de fragments
    private final int originalType;       // Type de message original (UByte comme int)
    private final byte[] data;            // Données du fragment

    public FragmentPayload(byte[] fragmentID, int index, int total, int originalType, byte[] data) {
        this.fragmentID = fragmentID;
        this.index = index;
        this.total = total;
        this.originalType = originalType;
        this.data = data;
    }

    // Getters
    public byte[] getFragmentID() { return fragmentID; }
    public int getIndex() { return index; }
    public int getTotal() { return total; }
    public int getOriginalType() { return originalType; }
    public byte[] getData() { return data; }

    /**
     * Décode la charge utile d'un fragment à partir de données binaires.
     * @param payloadData Les données brutes à décoder.
     * @return Un objet FragmentPayload, ou null en cas d'erreur.
     */
    public static FragmentPayload decode(byte[] payloadData) {
        if (payloadData.length < HEADER_SIZE) {
            return null;
        }
        try {
            byte[] fragmentID = Arrays.copyOfRange(payloadData, 0, FRAGMENT_ID_SIZE);
            int index = ((payloadData[8] & 0xFF) << 8) | (payloadData[9] & 0xFF);
            int total = ((payloadData[10] & 0xFF) << 8) | (payloadData[11] & 0xFF);
            int originalType = payloadData[12] & 0xFF;
            byte[] data = Arrays.copyOfRange(payloadData, HEADER_SIZE, payloadData.length);

            return new FragmentPayload(fragmentID, index, total, originalType, data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Génère un ID de fragment aléatoire de 8 bytes.
     * @return un tableau de 8 bytes aléatoires.
     */
    public static byte[] generateFragmentID() {
        byte[] fragmentID = new byte[FRAGMENT_ID_SIZE];
        new SecureRandom().nextBytes(fragmentID);
        return fragmentID;
    }

    /**
     * Encode la charge utile du fragment en données binaires.
     * @return Le tableau de bytes encodé.
     */
    public byte[] encode() {
        byte[] payload = new byte[HEADER_SIZE + data.length];

        System.arraycopy(fragmentID, 0, payload, 0, FRAGMENT_ID_SIZE);

        payload[8] = (byte) ((index >> 8) & 0xFF);
        payload[9] = (byte) (index & 0xFF);

        payload[10] = (byte) ((total >> 8) & 0xFF);
        payload[11] = (byte) (total & 0xFF);

        payload[12] = (byte) (originalType & 0xFF);

        if (data.length > 0) {
            System.arraycopy(data, 0, payload, HEADER_SIZE, data.length);
        }

        return payload;
    }

    /**
     * Obtient l'ID du fragment sous forme de chaîne hexadécimale pour le débogage.
     * @return La chaîne hexadécimale de l'ID.
     */
    public String getFragmentIDString() {
        return BinaryEncodingUtils.hexEncodedString(fragmentID);
    }

    /**
     * Valide les contraintes de la charge utile du fragment.
     * @return true si le fragment est valide, false sinon.
     */
    public boolean isValid() {
        return fragmentID.length == FRAGMENT_ID_SIZE &&
               index >= 0 &&
               total > 0 &&
               index < total &&
               data.length > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FragmentPayload that = (FragmentPayload) o;
        return index == that.index &&
               total == that.total &&
               originalType == that.originalType &&
               Arrays.equals(fragmentID, that.fragmentID) &&
               Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(index, total, originalType);
        result = 31 * result + Arrays.hashCode(fragmentID);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "FragmentPayload(fragmentID=" + getFragmentIDString() +
               ", index=" + index +
               ", total=" + total +
               ", originalType=" + originalType +
               ", dataSize=" + data.length + ")";
    }
}
