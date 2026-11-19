package com.gtnhplanner.core.base;

/** JavaScript's arithmetic where it differs from Java's and the website's numbers lean on it. */
public final class JsMath {

    private JsMath() {}

    /**
     * JavaScript's {@code Math.round}: the nearest whole number, a half towards +infinity ({@code -2.5} is {@code -2},
     * Java's {@code Math.rint} gives an even one); a negative number that rounds to zero is {@code -0}. NaN and the
     * infinities stay as they are.
     */
    public static double round(final double n) {
        if (n != n || n == Double.POSITIVE_INFINITY || n == Double.NEGATIVE_INFINITY) return n;
        final double floor = Math.floor(n);
        final double r = n - floor >= 0.5 ? floor + 1 : floor;
        return r == 0 && (n < 0 || 1 / n < 0) ? -0.0 : r;
    }

    private static final long MANTISSA = 0x000F_FFFF_FFFF_FFFFL, HIDDEN = 1L << 52, SIGN = Long.MIN_VALUE;

    /**
     * Java's and JavaScript's {@code x % y} on doubles, exact on every target: the remainder of x by y with the sign
     * of x and a magnitude below y's (NaN when y is 0, x is infinite or either is NaN; x when y is infinite or |x| is
     * below |y|). TeaVM's WebAssembly GC target computes the operator as {@code x - trunc(x / y) * y}, which is wrong
     * whenever the quotient does not fit a double's digits, so core code writes this instead of {@code %} on a double
     * (the purity gate's remainder rule).
     */
    public static double rem(final double x, final double y) {
        final long xb = Double.doubleToRawLongBits(x), yb = Double.doubleToRawLongBits(y);
        int ex = (int) (xb >>> 52 & 0x7FF), ey = (int) (yb >>> 52 & 0x7FF);
        if (y == 0 || x != x || y != y || ex == 0x7FF) return Double.NaN;
        final long signless = xb & ~SIGN;
        if (signless <= (yb & ~SIGN)) {
            // The magnitudes order like their bits. Smaller: x itself. Equal: a zero with x's sign.
            return signless == (yb & ~SIGN) ? Double.longBitsToDouble(xb & SIGN) : x;
        }
        long mx = signless & MANTISSA, my = yb & ~SIGN & MANTISSA;
        // Both as whole numbers of the smallest step, the leading one at bit 52; a subnormal is shifted up to it.
        if (ex == 0) {
            final int shift = Long.numberOfLeadingZeros(mx) - 11;
            mx <<= shift;
            ex = 1 - shift;
        } else {
            mx |= HIDDEN;
        }
        if (ey == 0) {
            final int shift = Long.numberOfLeadingZeros(my) - 11;
            my <<= shift;
            ey = 1 - shift;
        } else {
            my |= HIDDEN;
        }
        // Long division by subtraction, one bit of the exponent gap at a time.
        for (; ex > ey; ex--) {
            final long d = mx - my;
            if (d >= 0) {
                if (d == 0) return Double.longBitsToDouble(xb & SIGN);
                mx = d;
            }
            mx <<= 1;
        }
        final long d = mx - my;
        if (d >= 0) {
            if (d == 0) return Double.longBitsToDouble(xb & SIGN);
            mx = d;
        }
        for (; mx >>> 52 == 0; mx <<= 1) ex--;
        // Back to a double: a normal number drops its leading one into the exponent, a subnormal shifts down.
        final long bits = ex > 0 ? mx - HIDDEN | (long) ex << 52 : mx >>> 1 - ex;
        return Double.longBitsToDouble(bits | xb & SIGN);
    }
}
