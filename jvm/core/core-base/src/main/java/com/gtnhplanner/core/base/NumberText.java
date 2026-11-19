package com.gtnhplanner.core.base;

import java.math.BigInteger;

import javax.annotation.Nullable;

/**
 * Numbers to text and back, spelled exactly as JavaScript spells them, with the same characters on every JVM and
 * under the web compile. Use it wherever a number becomes text that is data: plan JSON, codes, hashes, keys, golden
 * files, anything compared or parsed again.
 * <p>
 * Why not the JDK: {@code Double.toString} spells numbers its own way ({@code 1.0E10} where the website writes
 * {@code 10000000000}), Java 8's is not always the shortest spelling (players run Java 8 to 25), and the web compile's
 * is another one again. Here the digits come from exact integer arithmetic, so the answer does not depend on the
 * host's floating-point library or formatter.
 */
public final class NumberText {

    private NumberText() {}

    private static final double TWO_53 = 9007199254740992.0;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger FOUR = BigInteger.valueOf(4);

    /** 1e0 to 1e22: every one exact as a double. */
    private static final double[] POW10 = { 1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
        1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22 };

    /**
     * JavaScript's {@code String(n)}: the shortest digits that read back as {@code n} (the closest such digits to
     * {@code n} when there are two, the even one on a tie), whole numbers below 1e21 without a point, exponent form
     * from 1e21 and below 1e-6 ({@code 1e+21}, {@code 1.5e-7}); {@code -0} is {@code "0"}, and {@code NaN},
     * {@code Infinity}, {@code -Infinity}.
     */
    public static String of(final double n) {
        if (n != n) return "NaN";
        if (n == 0) return "0";
        if (n == Double.POSITIVE_INFINITY) return "Infinity";
        if (n == Double.NEGATIVE_INFINITY) return "-Infinity";
        return n < 0 ? "-" + positive(-n) : positive(n);
    }

    /** {@code JSON.stringify(n)}: as {@link #of}, and {@code null} for NaN and the infinities. */
    public static String json(final double n) {
        if (n != n || n == Double.POSITIVE_INFINITY || n == Double.NEGATIVE_INFINITY) return "null";
        return of(n);
    }

    /**
     * JavaScript's {@code n.toFixed(digits)}: {@code n}'s exact binary value rounded to {@code digits} places, a half
     * rounding away from zero ({@code 0.15} is {@code 0.1499999...}, so {@code "0.1"}; {@code 2.5} is {@code "3"} at no
     * places). From 1e21 up it is {@link #of}; a negative number that rounds to zero keeps its sign ({@code "-0.00"}).
     * Java's {@code String.format("%.1f")} differs: it rounds the shortest decimal, not the value.
     *
     * @param digits 0 to 100, as JavaScript allows.
     */
    public static String fixed(final double n, final int digits) {
        if (digits < 0 || digits > 100) throw new IllegalArgumentException("toFixed digits out of range: " + digits);
        if (n != n) return "NaN";
        if (Math.abs(n) >= 1e21) return of(n);
        final boolean negative = n < 0;
        final double x = negative ? -n : n;
        final StringBuilder out = new StringBuilder();
        if (negative) out.append('-');
        String m = roundedScaled(x, digits).toString();
        if (digits != 0) {
            int k = m.length();
            if (k <= digits) {
                final StringBuilder z = new StringBuilder();
                for (int i = 0; i < digits + 1 - k; i++) z.append('0');
                m = z.append(m)
                    .toString();
                k = digits + 1;
            }
            out.append(m, 0, k - digits)
                .append('.')
                .append(m, k - digits, m.length());
        } else {
            out.append(m);
        }
        return out.toString();
    }

    /**
     * JavaScript's {@code Number(s)}: surrounding white space (JavaScript's set) ignored, blank is 0, {@code Infinity}
     * with or without a sign, {@code 0x}, {@code 0o} and {@code 0b} integers (no sign), decimals with an optional
     * point and exponent ({@code "1."}, {@code ".5"}, {@code "-2e3"}); anything else is NaN. The value is the double
     * nearest the text, a tie to the even one. {@code null} reads as 0.
     */
    public static double parse(@Nullable final String s) {
        if (s == null) return 0;
        int start = 0;
        int end = s.length();
        while (start < end && isJsSpace(s.charAt(start))) start++;
        while (end > start && isJsSpace(s.charAt(end - 1))) end--;
        if (start == end) return 0;
        final String t = s.substring(start, end);
        switch (t) {
            case "Infinity":
            case "+Infinity":
                return Double.POSITIVE_INFINITY;
            case "-Infinity":
                return Double.NEGATIVE_INFINITY;
            default:
                break;
        }
        if (t.length() > 2 && t.charAt(0) == '0') {
            final char p = t.charAt(1);
            final int radix = p == 'x' || p == 'X' ? 16 : p == 'o' || p == 'O' ? 8 : p == 'b' || p == 'B' ? 2 : 0;
            if (radix != 0) return radixInteger(t, radix);
        }
        return decimal(t);
    }

    // --- String(n) ---

    private static String positive(final double v) {
        // Below 2^53 a whole number is its own shortest spelling.
        if (v < TWO_53 && v == Math.floor(v)) return Long.toString((long) v);
        final StringBuilder digits = new StringBuilder(17);
        final int point = shortest(v, digits);
        return layout(digits.toString(), point);
    }

    /**
     * The shortest digits that read back as {@code v} (finite, above 0), into {@code out}; returns the point's place
     * {@code k}: {@code v} is about {@code 0.d1d2... * 10^k}. Burger and Dybvig's free-format method on exact integers:
     * {@code r/s} is the value, {@code mPlus/s} and {@code mMinus/s} half the gaps to the doubles either side, and a
     * digit string stops as soon as it lies inside that interval (its ends included when the significand is even, as
     * reading a decimal rounds a tie to even).
     */
    static int shortest(final double v, final StringBuilder out) {
        final long bits = Double.doubleToRawLongBits(v);
        final int biased = (int) ((bits >>> 52) & 0x7ff);
        long f = bits & 0xfffffffffffffL;
        final int e;
        if (biased == 0) {
            e = -1074;
        } else {
            f |= 1L << 52;
            e = biased - 1075;
        }
        final boolean even = (f & 1) == 0;
        // At a power of two (not the smallest normal) the double below is half as far away as the one above.
        final boolean lowerCloser = biased > 1 && f == 1L << 52;
        final BigInteger bf = BigInteger.valueOf(f);
        BigInteger r;
        BigInteger s;
        BigInteger mPlus;
        BigInteger mMinus;
        if (e >= 0) {
            final BigInteger ulp = BigInteger.ONE.shiftLeft(e);
            if (!lowerCloser) {
                r = bf.shiftLeft(e + 1);
                s = TWO;
                mPlus = ulp;
                mMinus = ulp;
            } else {
                r = bf.shiftLeft(e + 2);
                s = FOUR;
                mPlus = ulp.shiftLeft(1);
                mMinus = ulp;
            }
        } else if (!lowerCloser) {
            r = bf.shiftLeft(1);
            s = BigInteger.ONE.shiftLeft(1 - e);
            mPlus = BigInteger.ONE;
            mMinus = BigInteger.ONE;
        } else {
            r = bf.shiftLeft(2);
            s = BigInteger.ONE.shiftLeft(2 - e);
            mPlus = TWO;
            mMinus = BigInteger.ONE;
        }
        // An estimate of the point's place, corrected exactly below, so the host's log10 cannot change the answer.
        int k = (int) Math.ceil(Math.log10(v) - 1e-10);
        if (k >= 0) {
            s = s.multiply(BigInteger.TEN.pow(k));
        } else {
            final BigInteger scale = BigInteger.TEN.pow(-k);
            r = r.multiply(scale);
            mPlus = mPlus.multiply(scale);
            mMinus = mMinus.multiply(scale);
        }
        while (even ? r.add(mPlus)
            .compareTo(s) >= 0
            : r.add(mPlus)
                .compareTo(s) > 0) {
            s = s.multiply(BigInteger.TEN);
            k++;
        }
        while (true) {
            final int c = r.add(mPlus)
                .multiply(BigInteger.TEN)
                .compareTo(s);
            if (even ? c >= 0 : c > 0) break;
            r = r.multiply(BigInteger.TEN);
            mPlus = mPlus.multiply(BigInteger.TEN);
            mMinus = mMinus.multiply(BigInteger.TEN);
            k--;
        }
        while (true) {
            r = r.multiply(BigInteger.TEN);
            mPlus = mPlus.multiply(BigInteger.TEN);
            mMinus = mMinus.multiply(BigInteger.TEN);
            final BigInteger[] qr = r.divideAndRemainder(s);
            int d = qr[0].intValue();
            r = qr[1];
            final boolean low = even ? r.compareTo(mMinus) <= 0 : r.compareTo(mMinus) < 0;
            final boolean high = even ? r.add(mPlus)
                .compareTo(s) >= 0
                : r.add(mPlus)
                    .compareTo(s) > 0;
            if (!low && !high) {
                out.append((char) ('0' + d));
                continue;
            }
            if (low && high) {
                final int c = r.shiftLeft(1)
                    .compareTo(s);
                if (c > 0 || c == 0 && (d & 1) == 1) d++;
            } else if (high) {
                d++;
            }
            if (d > 9) throw new IllegalStateException("digit overflow");
            out.append((char) ('0' + d));
            break;
        }
        return k;
    }

    /** ECMAScript's Number::toString layout of digits {@code s} with the point at place {@code n}. */
    private static String layout(final String s, final int n) {
        final int k = s.length();
        final StringBuilder out = new StringBuilder(k + 8);
        if (k <= n && n <= 21) {
            out.append(s);
            for (int i = k; i < n; i++) out.append('0');
        } else if (0 < n && n <= 21) {
            out.append(s, 0, n)
                .append('.')
                .append(s, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.");
            for (int i = n; i < 0; i++) out.append('0');
            out.append(s);
        } else {
            out.append(s.charAt(0));
            if (k > 1) out.append('.')
                .append(s, 1, k);
            final int exp = n - 1;
            out.append('e')
                .append(exp < 0 ? '-' : '+')
                .append(Math.abs(exp));
        }
        return out.toString();
    }

    // --- toFixed ---

    /** {@code x * 10^digits} rounded to a whole number, a half up, from {@code x}'s exact value ({@code x >= 0}). */
    private static BigInteger roundedScaled(final double x, final int digits) {
        if (x == 0) return BigInteger.ZERO;
        final long bits = Double.doubleToRawLongBits(x);
        final int biased = (int) ((bits >>> 52) & 0x7ff);
        long f = bits & 0xfffffffffffffL;
        final int e;
        if (biased == 0) {
            e = -1074;
        } else {
            f |= 1L << 52;
            e = biased - 1075;
        }
        final BigInteger scaled = BigInteger.valueOf(f)
            .multiply(BigInteger.TEN.pow(digits));
        if (e >= 0) return scaled.shiftLeft(e);
        final BigInteger q = scaled.shiftRight(-e);
        return scaled.testBit(-e - 1) ? q.add(BigInteger.ONE) : q;
    }

    // --- Number(s) ---

    /** JavaScript's WhiteSpace and LineTerminator characters, which {@code Number(s)} ignores around the number. */
    private static boolean isJsSpace(final char c) {
        switch (c) {
            case '\t':
            case '\n':
            case 0x0B:
            case '\f':
            case '\r':
            case ' ':
            case 0xA0:
            case 0x1680:
            case 0x2028:
            case 0x2029:
            case 0x202F:
            case 0x205F:
            case 0x3000:
            case 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    private static double radixInteger(final String t, final int radix) {
        BigInteger value = BigInteger.ZERO;
        final BigInteger big = BigInteger.valueOf(radix);
        for (int i = 2; i < t.length(); i++) {
            final int d = asciiDigit(t.charAt(i));
            if (d < 0 || d >= radix) return Double.NaN;
            value = value.multiply(big)
                .add(BigInteger.valueOf(d));
        }
        return value.signum() == 0 ? 0 : ratioToDouble(value, BigInteger.ONE);
    }

    private static int asciiDigit(final char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'z') return c - 'a' + 10;
        if (c >= 'A' && c <= 'Z') return c - 'A' + 10;
        return -1;
    }

    private static double decimal(final String t) {
        int i = 0;
        final int len = t.length();
        boolean negative = false;
        if (t.charAt(0) == '+' || t.charAt(0) == '-') {
            negative = t.charAt(0) == '-';
            i++;
        }
        final StringBuilder digits = new StringBuilder();
        int seen = 0;
        long exp10 = 0;
        while (i < len && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
            if (digits.length() > 0 || t.charAt(i) != '0') digits.append(t.charAt(i));
            seen++;
            i++;
        }
        if (i < len && t.charAt(i) == '.') {
            i++;
            while (i < len && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
                if (digits.length() > 0 || t.charAt(i) != '0') digits.append(t.charAt(i));
                exp10--;
                seen++;
                i++;
            }
        }
        if (seen == 0) return Double.NaN;
        if (i < len && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
            i++;
            boolean expNegative = false;
            if (i < len && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
                expNegative = t.charAt(i) == '-';
                i++;
            }
            final int expStart = i;
            long exp = 0;
            while (i < len && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
                // Past this any exponent makes infinity or zero; saturate rather than overflow.
                if (exp < 1_000_000_000L) exp = exp * 10 + (t.charAt(i) - '0');
                i++;
            }
            if (i == expStart) return Double.NaN;
            exp10 += expNegative ? -exp : exp;
        }
        if (i != len) return Double.NaN;
        // Trailing zeros of the digits go into the exponent.
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
            exp10++;
        }
        final double v = end == 0 ? 0 : decimalToDouble(digits.substring(0, end), exp10);
        return negative ? -v : v;
    }

    /** The double nearest {@code digits * 10^exp10} ({@code digits} a positive integer, no leading zero). */
    private static double decimalToDouble(final String digits, final long exp10) {
        final int count = digits.length();
        if (count - 1 + exp10 >= 309) return Double.POSITIVE_INFINITY;
        if (count + exp10 < -324) return 0;
        if (count <= 15 && Math.abs(exp10) <= 22) {
            // Both exact as doubles, so one correctly rounded operation is the correctly rounded answer.
            final double m = Long.parseLong(digits);
            return exp10 >= 0 ? m * POW10[(int) exp10] : m / POW10[(int) -exp10];
        }
        final BigInteger m = new BigInteger(digits);
        if (exp10 >= 0) return ratioToDouble(m.multiply(BigInteger.TEN.pow((int) exp10)), BigInteger.ONE);
        return ratioToDouble(m, BigInteger.TEN.pow((int) -exp10));
    }

    /** The double nearest {@code num / den} (both above 0), a tie to the even one; overflow is infinity. */
    static double ratioToDouble(final BigInteger num, final BigInteger den) {
        final int e2 = num.bitLength() - den.bitLength();
        if (e2 > 1025) return Double.POSITIVE_INFINITY;
        if (e2 < -1080) return 0;
        // The value is q * 2^u, q with 53 bits (fewer only in the subnormal range, where u is -1074).
        int u = Math.max(e2 - 53, -1074);
        while (true) {
            BigInteger n = num;
            BigInteger d = den;
            if (u < 0) n = n.shiftLeft(-u);
            else d = d.shiftLeft(u);
            final BigInteger[] qr = n.divideAndRemainder(d);
            final int length = qr[0].bitLength();
            if (length > 53) {
                u += length - 53;
                continue;
            }
            if (length < 53 && u > -1074) {
                u -= Math.min(53 - length, u + 1074);
                continue;
            }
            long m = qr[0].longValue();
            final int c = qr[1].shiftLeft(1)
                .compareTo(d);
            if (c > 0 || c == 0 && (m & 1) == 1) m++;
            if (m == 1L << 53) {
                m >>= 1;
                u++;
            }
            if (m < 1L << 52) return Double.longBitsToDouble(m);
            final long field = u + 1075L;
            if (field >= 2047) return Double.POSITIVE_INFINITY;
            return Double.longBitsToDouble(field << 52 | m & 0xfffffffffffffL);
        }
    }
}
