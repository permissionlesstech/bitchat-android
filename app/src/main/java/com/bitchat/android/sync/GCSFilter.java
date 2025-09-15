package com.bitchat.android.sync;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implémentation du filtre Golomb-Coded Set (GCS) pour la synchronisation.
 */
public final class GCSFilter {

    private GCSFilter() {}

    public static class Params {
        public final int p;
        public final long m;
        public final byte[] data;
        public Params(int p, long m, byte[] data) {
            this.p = p;
            this.m = m;
            this.data = data;
        }
    }

    public static int deriveP(double targetFpr) {
        double f = Math.max(0.000001, Math.min(0.25, targetFpr));
        return Math.max(1, (int) Math.ceil(Math.log(1.0 / f) / Math.log(2.0)));
    }

    public static Params buildFilter(List<byte[]> ids, int maxBytes, double targetFpr) {
        int p = deriveP(targetFpr);
        int n = ids.size();
        long m = (long) n << p;

        List<Long> mapped = ids.stream().map(GCSFilter::h64).sorted().collect(Collectors.toList());

        byte[] encoded = encode(mapped, p);

        return new Params(p, m, encoded);
    }

    public static long[] decodeToSortedSet(int p, long m, byte[] data) {
        List<Long> values = new ArrayList<>();
        BitReader reader = new BitReader(data);
        long acc = 0L;
        long mask = (1L << p) - 1L;
        while (!reader.isEof()) {
            long q = 0L;
            while (true) {
                Integer bit = reader.readBit();
                if (bit == null || bit == 0) break;
                q++;
            }
            if (reader.isEof()) break;
            Long r = reader.readBits(p);
            if (r == null) break;
            long x = (q << p) + r + 1;
            acc += x;
            if (acc >= m) break;
            values.add(acc);
        }
        return values.stream().mapToLong(l -> l).toArray();
    }

    public static boolean contains(long[] sortedValues, long candidate) {
        return Arrays.binarySearch(sortedValues, candidate) >= 0;
    }

    private static long h64(byte[] id16) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(id16);
            byte[] d = md.digest();
            long x = 0L;
            for (int i = 0; i < 8; i++) {
                x = (x << 8) | (d[i] & 0xFFL);
            }
            return x & 0x7FFFFFFFFFFFFFFFL; // Positif
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] encode(List<Long> sorted, int p) {
        BitWriter bw = new BitWriter();
        long prev = 0L;
        long mask = (1L << p) - 1L;
        for (long v : sorted) {
            long delta = v - prev;
            prev = v;
            long q = (delta - 1) >>> p;
            long r = (delta - 1) & mask;
            for (int i = 0; i < q; i++) bw.writeBit(1);
            bw.writeBit(0);
            bw.writeBits(r, p);
        }
        return bw.toByteArray();
    }

    private static class BitWriter {
        private final ArrayList<Byte> buf = new ArrayList<>();
        private int cur = 0;
        private int nbits = 0;
        void writeBit(int bit) {
            cur = (cur << 1) | (bit & 1);
            nbits++;
            if (nbits == 8) {
                buf.add((byte) cur);
                cur = 0;
                nbits = 0;
            }
        }
        void writeBits(long value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                writeBit((int)((value >>> i) & 1L));
            }
        }
        byte[] toByteArray() {
            if (nbits > 0) {
                buf.add((byte) (cur << (8 - nbits)));
            }
            byte[] result = new byte[buf.size()];
            for(int i = 0; i < buf.size(); i++) result[i] = buf.get(i);
            return result;
        }
    }

    private static class BitReader {
        private final byte[] data;
        private int i = 0;
        private int nleft = 8;
        private int cur;
        BitReader(byte[] data) {
            this.data = data;
            this.cur = data.length > 0 ? (data[0] & 0xFF) : 0;
        }
        boolean isEof() { return i >= data.length; }
        Integer readBit() {
            if (isEof()) return null;
            int bit = (cur >>> 7) & 1;
            cur = (cur << 1) & 0xFF;
            nleft--;
            if (nleft == 0) {
                i++;
                if (i < data.length) {
                    cur = data[i] & 0xFF;
                    nleft = 8;
                }
            }
            return bit;
        }
        Long readBits(int count) {
            long v = 0L;
            for (int k = 0; k < count; k++) {
                Integer b = readBit();
                if (b == null) return null;
                v = (v << 1) | b;
            }
            return v;
        }
    }
}
