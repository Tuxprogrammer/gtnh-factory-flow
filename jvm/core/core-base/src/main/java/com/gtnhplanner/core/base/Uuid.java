package com.gtnhplanner.core.base;

import java.util.Objects;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

/**
 * A 128-bit id that behaves exactly as {@code java.util.UUID} does on the JDK, on every target: the same
 * {@link #hashCode}, {@link #equals}, {@link #compareTo} (signed, most significant half first, which is not the order
 * of the strings), {@link #toString}, {@link #fromString} (Java 9 and later's rules) and {@link #nameUUIDFromBytes}
 * (MD5, version 3). The core uses it instead of {@code java.util.UUID}, which the web compile replaces with a string
 * that hashes and sorts differently, so the solver's maps and sorts, the ids made from website ids, and every saved id
 * stay the same on both sides.
 * <p>
 * One deliberate difference: {@link #fromString} reads ASCII hex digits only, where the JDK also takes other
 * scripts' digits and full-width letters; those strings are rejected here.
 */
public final class Uuid implements Comparable<Uuid> {

    private final long msb;
    private final long lsb;

    public Uuid(final long mostSigBits, final long leastSigBits) {
        msb = mostSigBits;
        lsb = leastSigBits;
    }

    public long getMostSignificantBits() {
        return msb;
    }

    public long getLeastSignificantBits() {
        return lsb;
    }

    /** The version number: 4 random, 3 name-based. */
    public int version() {
        return (int) ((msb >> 12) & 0x0f);
    }

    /** The variant number: 2 for the IETF layout these ids use. */
    public int variant() {
        return (int) ((lsb >>> (64 - (lsb >>> 62))) & (lsb >> 63));
    }

    /**
     * A random (version 4) id from the installed random source. The JDK draws from a secure generator; a host
     * installs one with {@link #installRandom} (the mod: {@code SecureRandom}), and until then the source is
     * {@link Math#random}.
     */
    public static Uuid randomUUID() {
        final LongSupplier random = randomSource;
        final long hi = random.getAsLong();
        final long lo = random.getAsLong();
        return new Uuid(hi & ~0xf000L | 0x4000L, lo & 0x3fffffffffffffffL | 0x8000000000000000L);
    }

    /** Installs the host's source of random 64-bit values for {@link #randomUUID}. */
    public static void installRandom(final LongSupplier random) {
        randomSource = Objects.requireNonNull(random, "random");
    }

    private static LongSupplier randomSource = () -> (long) (Math.random() * 0x1p32) << 32
        | (long) (Math.random() * 0x1p32);

    /** The name-based (version 3) id of {@code name}: its MD5 digest with the version and variant set. */
    public static Uuid nameUUIDFromBytes(final byte[] name) {
        final byte[] b = Md5.digest(name);
        b[6] = (byte) (b[6] & 0x0f | 0x30);
        b[8] = (byte) (b[8] & 0x3f | 0x80);
        long m = 0;
        long l = 0;
        for (int i = 0; i < 8; i++) m = m << 8 | b[i] & 0xff;
        for (int i = 8; i < 16; i++) l = l << 8 | b[i] & 0xff;
        return new Uuid(m, l);
    }

    /**
     * The id {@code name} spells, as the JDK reads it from Java 9 on: the usual 36-character form, or five
     * dash-separated hex groups of any length up to 36 characters in all, each group taking a sign and keeping as many
     * low bits as its place holds ({@code "1-2-3-4-5"} is {@code 00000001-0002-0003-0004-000000000005}).
     *
     * @throws IllegalArgumentException when it is not one ({@link NumberFormatException} for a bad group, as the JDK).
     */
    public static Uuid fromString(final String name) {
        if (name.length() == 36 && name.charAt(8) == '-'
            && name.charAt(13) == '-'
            && name.charAt(18) == '-'
            && name.charAt(23) == '-') {
            final long msb1 = nibbles(name, 0);
            final long msb2 = nibbles(name, 4);
            final long msb3 = nibbles(name, 9);
            final long msb4 = nibbles(name, 14);
            final long lsb1 = nibbles(name, 19);
            final long lsb2 = nibbles(name, 24);
            final long lsb3 = nibbles(name, 28);
            final long lsb4 = nibbles(name, 32);
            if ((msb1 | msb2 | msb3 | msb4 | lsb1 | lsb2 | lsb3 | lsb4) >= 0) {
                return new Uuid(msb1 << 48 | msb2 << 32 | msb3 << 16 | msb4, lsb1 << 48 | lsb2 << 32 | lsb3 << 16 | lsb4);
            }
        }
        final int len = name.length();
        if (len > 36) throw new IllegalArgumentException("UUID string too large");
        final int dash1 = name.indexOf('-');
        final int dash2 = name.indexOf('-', dash1 + 1);
        final int dash3 = name.indexOf('-', dash2 + 1);
        final int dash4 = name.indexOf('-', dash3 + 1);
        final int dash5 = name.indexOf('-', dash4 + 1);
        if (dash4 < 0 || dash5 >= 0) throw new IllegalArgumentException("Invalid UUID string: " + name);
        long m = parseHex(name, 0, dash1) & 0xffffffffL;
        m <<= 16;
        m |= parseHex(name, dash1 + 1, dash2) & 0xffffL;
        m <<= 16;
        m |= parseHex(name, dash2 + 1, dash3) & 0xffffL;
        long l = parseHex(name, dash3 + 1, dash4) & 0xffffL;
        l <<= 48;
        l |= parseHex(name, dash4 + 1, len) & 0xffffffffffffL;
        return new Uuid(m, l);
    }

    /** Four hex digits at {@code at} as a number, or -1 when one is not a hex digit. */
    private static long nibbles(final String s, final int at) {
        long v = 0;
        for (int i = at; i < at + 4; i++) {
            final int d = hexDigit(s.charAt(i));
            if (d < 0) return -1;
            v = v << 4 | d;
        }
        return v;
    }

    private static int hexDigit(final char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** {@code Long.parseLong(s, begin, end, 16)}: an optional sign, at least one digit, no overflow. */
    private static long parseHex(final String s, final int begin, final int end) {
        if (begin >= end) throw new NumberFormatException("Invalid UUID string: " + s);
        int i = begin;
        boolean negative = false;
        long limit = -Long.MAX_VALUE;
        final char first = s.charAt(i);
        if (first == '-' || first == '+') {
            if (first == '-') {
                negative = true;
                limit = Long.MIN_VALUE;
            }
            i++;
            if (i == end) throw new NumberFormatException("Invalid UUID string: " + s);
        }
        final long multmin = limit / 16;
        long result = 0;
        // Accumulated negatively, as the JDK does, so Long.MIN_VALUE fits.
        for (; i < end; i++) {
            final int d = hexDigit(s.charAt(i));
            if (d < 0 || result < multmin) throw new NumberFormatException("Invalid UUID string: " + s);
            result *= 16;
            if (result < limit + d) throw new NumberFormatException("Invalid UUID string: " + s);
            result -= d;
        }
        return negative ? result : -result;
    }

    /** The 36-character form, lower-case hex: {@code 123e4567-e89b-12d3-a456-426614174000}. */
    @Override
    public String toString() {
        final char[] out = new char[36];
        hex(out, 0, msb >>> 32, 8);
        out[8] = '-';
        hex(out, 9, msb >>> 16, 4);
        out[13] = '-';
        hex(out, 14, msb, 4);
        out[18] = '-';
        hex(out, 19, lsb >>> 48, 4);
        out[23] = '-';
        hex(out, 24, lsb, 12);
        return new String(out);
    }

    private static void hex(final char[] out, final int at, final long value, final int digits) {
        for (int i = digits - 1; i >= 0; i--) {
            out[at + i] = "0123456789abcdef".charAt((int) (value >>> ((digits - 1 - i) * 4)) & 0xf);
        }
    }

    @Override
    public int hashCode() {
        final long hilo = msb ^ lsb;
        return (int) (hilo >> 32) ^ (int) hilo;
    }

    @Override
    public boolean equals(@Nullable final Object obj) {
        if (!(obj instanceof Uuid id)) return false;
        return msb == id.msb && lsb == id.lsb;
    }

    @Override
    public int compareTo(final Uuid val) {
        final int high = Long.compare(msb, val.msb);
        return high != 0 ? high : Long.compare(lsb, val.lsb);
    }

    /** RFC 1321 MD5, for {@link #nameUUIDFromBytes}. Its constant table is written out, not computed with sine. */
    static final class Md5 {

        private Md5() {}

        private static final int[] S = { 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5,
            9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10,
            15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21 };

        private static final int[] K = { 0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a,
            0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e,
            0x49b40821, 0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
            0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a, 0xfffa3942,
            0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa,
            0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665, 0xf4292244, 0x432aff97, 0xab9423a7,
            0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
            0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391 };

        static byte[] digest(final byte[] message) {
            final int len = message.length;
            final int blocks = ((len + 8) >>> 6) + 1;
            final byte[] padded = new byte[blocks << 6];
            System.arraycopy(message, 0, padded, 0, len);
            padded[len] = (byte) 0x80;
            final long bits = (long) len << 3;
            for (int i = 0; i < 8; i++) padded[padded.length - 8 + i] = (byte) (bits >>> (8 * i));
            int a0 = 0x67452301;
            int b0 = 0xefcdab89;
            int c0 = 0x98badcfe;
            int d0 = 0x10325476;
            final int[] m = new int[16];
            for (int blk = 0; blk < blocks; blk++) {
                for (int i = 0; i < 16; i++) {
                    final int o = (blk << 6) + (i << 2);
                    m[i] = padded[o] & 0xff | (padded[o + 1] & 0xff) << 8
                        | (padded[o + 2] & 0xff) << 16
                        | (padded[o + 3] & 0xff) << 24;
                }
                int a = a0;
                int b = b0;
                int c = c0;
                int d = d0;
                for (int i = 0; i < 64; i++) {
                    final int f;
                    final int g;
                    if (i < 16) {
                        f = b & c | ~b & d;
                        g = i;
                    } else if (i < 32) {
                        f = d & b | ~d & c;
                        g = (5 * i + 1) & 15;
                    } else if (i < 48) {
                        f = b ^ c ^ d;
                        g = (3 * i + 5) & 15;
                    } else {
                        f = c ^ (b | ~d);
                        g = (7 * i) & 15;
                    }
                    final int t = d;
                    d = c;
                    c = b;
                    b = b + Integer.rotateLeft(a + f + K[i] + m[g], S[i]);
                    a = t;
                }
                a0 += a;
                b0 += b;
                c0 += c;
                d0 += d;
            }
            final byte[] out = new byte[16];
            final int[] h = { a0, b0, c0, d0 };
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[i * 4 + j] = (byte) (h[i] >>> (8 * j));
            return out;
        }
    }
}
