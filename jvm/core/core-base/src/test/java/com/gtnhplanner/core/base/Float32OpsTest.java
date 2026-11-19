package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** On the JVM Float32Ops is the JVM's own float arithmetic; the web build runs the same cases in Node on both targets. */
class Float32OpsTest {

    private static void same(final float expected, final float actual, final String what) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), what);
    }

    @Test
    void castsRoundLikeTheJvm() {
        final Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            final double d = Double.longBitsToDouble(random.nextLong());
            if (Double.isNaN(d)) continue;
            same((float) d, Float32Ops.of(d), "of(" + d + ")");
        }
        same(0.1f, Float32Ops.of(0.1), "0.1");
        same(16777216f, Float32Ops.of(16777217), "16777217");
        same(-0f, Float32Ops.of(-0.0), "negative zero");
        same(Float.POSITIVE_INFINITY, Float32Ops.of(1e300), "overflow");
    }

    @Test
    void arithmeticRoundsLikeTheJvm() {
        final Random random = new Random(11);
        for (int i = 0; i < 20000; i++) {
            final float a = Float.intBitsToFloat(random.nextInt()), b = Float.intBitsToFloat(random.nextInt());
            if (Float.isNaN(a) || Float.isNaN(b)) continue;
            same(a + b, Float32Ops.add(a, b), a + " + " + b);
            same(a - b, Float32Ops.sub(a, b), a + " - " + b);
            same(a * b, Float32Ops.mul(a, b), a + " * " + b);
            same(a / b, Float32Ops.div(a, b), a + " / " + b);
        }
        // Operands a float can hold exactly, the cases a double rounding would get wrong if it could.
        for (int i = 0; i < 20000; i++) {
            final float a = random.nextFloat() * (1 << random.nextInt(24)), b = random.nextFloat() * 3;
            same(a + b, Float32Ops.add(a, b), a + " + " + b);
            same(a * b, Float32Ops.mul(a, b), a + " * " + b);
            same(a / b, Float32Ops.div(a, b), a + " / " + b);
        }
    }

    @Test
    void integersRoundOnceToNearestEven() {
        final Random random = new Random(13);
        for (int i = 0; i < 20000; i++) {
            final int n = random.nextInt();
            same((float) n, Float32Ops.of(n), "of(" + n + ")");
        }
        for (int i = 0; i < 40000; i++) {
            // Near every tie a long can have: the bits just under a float's last place, and under a double's.
            final long base = random.nextLong() >> random.nextInt(63);
            for (final long delta : new long[] { 0, 1, -1, 1L << 28, (1L << 28) - 1, (1L << 28) + 1, 1L << 10, 1L << 11 }) {
                final long n = base + delta;
                same((float) n, Float32Ops.of(n), "of(" + n + ")");
            }
        }
        for (final long n : new long[] { Long.MIN_VALUE, Long.MAX_VALUE, (1L << 53) + 1, -(1L << 53) - 1,
            0x7FFFFFFFFFFFFC00L, 0x7FFFFF4000000001L, 16777217L, -16777217L, 0 }) {
            same((float) n, Float32Ops.of(n), "of(" + n + ")");
        }
    }
}
