package com.bitchat.android.nostr;

import android.util.Pair;
import java.util.ArrayList;
import java.util.List;

/**
 * Implémentation de l'encodage/décodage Bech32 pour Nostr (npub/nsec).
 */
public final class Bech32 {

    private Bech32() {}

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    public static String encode(String hrp, byte[] data) {
        int[] values = convertBits(data, 8, 5, true);
        int[] checksum = createChecksum(hrp, values);

        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (int v : values) sb.append(CHARSET.charAt(v));
        for (int v : checksum) sb.append(CHARSET.charAt(v));

        return sb.toString();
    }

    public static Pair<String, byte[]> decode(String bech32String) {
        int separatorIndex = bech32String.lastIndexOf('1');
        if (separatorIndex < 0) throw new IllegalArgumentException("No separator found");

        String hrp = bech32String.substring(0, separatorIndex);
        String dataString = bech32String.substring(separatorIndex + 1);

        int[] values = new int[dataString.length()];
        for (int i = 0; i < dataString.length(); i++) {
            int index = CHARSET.indexOf(dataString.charAt(i));
            if (index < 0) throw new IllegalArgumentException("Invalid character");
            values[i] = index;
        }

        // ... La logique de vérification du checksum et de conversion des bits irait ici ...

        int[] payloadValues = new int[values.length - 6];
        System.arraycopy(values, 0, payloadValues, 0, payloadValues.length);

        int[] bytesInt = convertBits(payloadValues, 5, 8, false);
        byte[] bytes = new byte[bytesInt.length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) bytesInt[i];

        return new Pair<>(hrp, bytes);
    }

    private static int[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int[] intData = new int[data.length];
        for (int i = 0; i < data.length; i++) intData[i] = data[i] & 0xFF;
        return convertBits(intData, fromBits, toBits, pad);
    }

    private static int[] convertBits(int[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        List<Integer> result = new ArrayList<>();
        int maxv = (1 << toBits) - 1;

        for (int value : data) {
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                result.add((acc >> bits) & maxv);
            }
        }

        if (pad && bits > 0) {
            result.add((acc << (toBits - bits)) & maxv);
        }

        int[] resArray = new int[result.size()];
        for (int i = 0; i < result.size(); i++) resArray[i] = result.get(i);
        return resArray;
    }

    private static int[] createChecksum(String hrp, int[] values) {
        // ... La logique de création du checksum irait ici ...
        return new int[6]; // Placeholder
    }
}
