package com.bitchat.android.model;

import com.bitchat.android.sync.SyncDefaults;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Paquet de synchronisation (REQUEST_SYNC) utilisant les paramètres GCS (Golomb-Coded Set).
 * Encodage TLV (type, longueur sur 16 bits, valeur) pour les champs.
 */
public final class RequestSyncPacket {

    public static final int MAX_ACCEPT_FILTER_BYTES = SyncDefaults.MAX_ACCEPT_FILTER_BYTES;

    private final int p;
    private final long m;
    private final byte[] data;

    public RequestSyncPacket(int p, long m, byte[] data) {
        this.p = p;
        this.m = m;
        this.data = data;
    }

    // Getters
    public int getP() { return p; }
    public long getM() { return m; }
    public byte[] getData() { return data; }

    /**
     * Encode le paquet en données binaires TLV.
     * @return Le tableau de bytes encodé.
     */
    public byte[] encode() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // P
            putTLV(buffer, 0x01, new byte[]{(byte) p});
            // M (uint32)
            long m32 = Math.min(m, 0xffff_ffffL);
            byte[] mBytes = new byte[]{
                (byte) ((m32 >> 24) & 0xFF),
                (byte) ((m32 >> 16) & 0xFF),
                (byte) ((m32 >> 8) & 0xFF),
                (byte) (m32 & 0xFF)
            };
            putTLV(buffer, 0x02, mBytes);
            // data
            putTLV(buffer, 0x03, data);
        } catch (IOException e) {
            // Ne devrait jamais arriver avec ByteArrayOutputStream
            return null;
        }
        return buffer.toByteArray();
    }

    private void putTLV(ByteArrayOutputStream buffer, int type, byte[] value) throws IOException {
        buffer.write((byte) type);
        int len = value.length;
        buffer.write((byte) ((len >> 8) & 0xFF));
        buffer.write((byte) (len & 0xFF));
        buffer.write(value);
    }

    /**
     * Décode le paquet à partir de données binaires.
     * @param data Les données à décoder.
     * @return Un objet RequestSyncPacket, ou null en cas d'erreur.
     */
    public static RequestSyncPacket decode(byte[] data) {
        int off = 0;
        Integer p = null;
        Long m = null;
        byte[] payload = null;

        while (off + 3 <= data.length) {
            int t = data[off++] & 0xFF;
            int len = ((data[off++] & 0xFF) << 8) | (data[off++] & 0xFF);
            if (off + len > data.length) return null;
            byte[] v = Arrays.copyOfRange(data, off, off + len);
            off += len;

            switch (t) {
                case 0x01:
                    if (len == 1) p = v[0] & 0xFF;
                    break;
                case 0x02:
                    if (len == 4) {
                        m = ((long)(v[0] & 0xFF) << 24) |
                            ((long)(v[1] & 0xFF) << 16) |
                            ((long)(v[2] & 0xFF) << 8) |
                            ((long)(v[3] & 0xFF));
                    }
                    break;
                case 0x03:
                    if (v.length > MAX_ACCEPT_FILTER_BYTES) return null;
                    payload = v;
                    break;
            }
        }

        if (p == null || m == null || payload == null) return null;
        if (p < 1 || m <= 0L) return null;

        return new RequestSyncPacket(p, m, payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestSyncPacket that = (RequestSyncPacket) o;
        return p == that.p && m == that.m && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(p, m);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}
