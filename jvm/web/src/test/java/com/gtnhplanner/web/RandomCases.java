package com.gtnhplanner.web;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.gtnhplanner.core.base.Float32;
import com.gtnhplanner.core.base.JsMath;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.base.Uuid;

/**
 * The large random comparison: cases made here on the JVM, answered by the JVM classes (core-base's NumberText,
 * JsMath and Uuid, and java.util.UUID for ids), written as one JSON array per line. tools/web-core/random.mjs replays
 * them against each compiled target, and checks the number cases against Node's own numbers too.
 *
 * <pre>
 *   RandomCases OUT [SEED [SCALE]]      SCALE multiplies every count (default 1: about 400,000 cases)
 * </pre>
 *
 * Lines (hex is a 64-bit pattern, 16 digits): ["of", hex, text, json], ["fixed", hex, digits, text or null when it must
 * throw], ["parse", text, hex], ["round", hex, hex], ["f32", hex, hex], ["id", msb, lsb, text, hash, version, variant], ["cmp", msb, lsb,
 * msb, lsb, signum], ["order", "msb:lsb,...", "i,j,..."], ["name", text, id], ["uparse", text, id or exception name].
 */
public final class RandomCases {

    private RandomCases() {}

    private static final String JS_SPACE = " \t\n\r\u000b\u000c         "
        + "        　﻿";

    private static String hex(final double d) {
        final String h = Long.toHexString(Double.doubleToRawLongBits(d));
        return "0000000000000000".substring(h.length()) + h;
    }

    private static String hex(final long l) {
        final String h = Long.toHexString(l);
        return "0000000000000000".substring(h.length()) + h;
    }

    /** JSON string, ASCII only (every other char as \\uXXXX), so no reader's encoding matters. */
    private static String q(final String s) {
        final StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c >= 0x20 && c < 0x7f) b.append(c);
            else b.append(String.format("\\u%04x", (int) c));
        }
        return b.append('"')
            .toString();
    }

    private static double pow10(final int e) {
        return Double.parseDouble("1e" + e);
    }

    /** A double from one of several families, so the cases are not all random bit patterns. */
    private static double number(final Random r) {
        switch (r.nextInt(12)) {
            case 0:
                return Double.longBitsToDouble(r.nextLong());
            case 1:
                return (r.nextInt(2_000_000_000) - 1_000_000_000) / pow10(r.nextInt(9));
            case 2:
                return r.nextInt(100_000) / (double) (1 + r.nextInt(999));
            case 3:
                return r.nextInt(1_000_000) * pow10(r.nextInt(40) - 20);
            case 4:
                return Math.nextUp(pow10(r.nextInt(640) - 323)) * (r.nextBoolean() ? 1 : -1);
            case 5:
                return Math.nextDown(pow10(r.nextInt(600) - 300));
            case 6:
                return (double) (1L << 53) + r.nextInt(64) - 32;
            case 7:
                return r.nextInt(2_000_000) / 2.0 - 500_000;
            case 8:
                return Double.longBitsToDouble(r.nextLong() & 0x800fffffffffffffL);
            case 9:
                return r.nextDouble() * pow10(r.nextInt(30) - 8) * (r.nextBoolean() ? 1 : -1);
            case 10:
                return pow10(21 + r.nextInt(3) - 1) * (0.9 + r.nextInt(200) / 1000.0);
            default:
                return (r.nextInt(2_000_001) - 1_000_000) / 8.0 / pow10(r.nextInt(5));
        }
    }

    private static String digits(final Random r, final int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append((char) ('0' + r.nextInt(10)));
        return b.toString();
    }

    /** A text Number(s) may read: a spelling of a number, a literal form, white space, or noise. */
    private static String numberText(final Random r) {
        final String ws = r.nextInt(4) == 0 ? "" + JS_SPACE.charAt(r.nextInt(JS_SPACE.length())) : "";
        final String ws2 = r.nextInt(4) == 0 ? "" + JS_SPACE.charAt(r.nextInt(JS_SPACE.length())) : "";
        final String core;
        switch (r.nextInt(14)) {
            case 0:
                core = NumberText.of(number(r));
                break;
            case 1:
                core = (r.nextBoolean() ? "-" : "") + digits(r, 1 + r.nextInt(25)) + "." + digits(r, r.nextInt(25));
                break;
            case 2:
                core = digits(r, 1 + r.nextInt(8)) + (r.nextBoolean() ? "e" : "E") + (r.nextBoolean() ? "+" : "-")
                    + r.nextInt(400);
                break;
            case 3:
                core = "." + digits(r, 1 + r.nextInt(20)) + "e" + (r.nextInt(700) - 350);
                break;
            case 4:
                core = digits(r, 300 + r.nextInt(300)); // far more digits than a double holds
                break;
            case 5:
                core = "0." + "0".repeat(r.nextInt(330)) + digits(r, 1 + r.nextInt(20));
                break;
            case 6:
                core = "0x" + Long.toHexString(r.nextLong() >>> r.nextInt(60));
                break;
            case 7:
                core = (r.nextBoolean() ? "0b" : "0o") + Long.toString(r.nextLong() >>> r.nextInt(60), r.nextBoolean() ? 2 : 8);
                break;
            case 8:
                final String[] odd = new String[] { "Infinity", "-Infinity", "+Infinity", "infinity", "INFINITY", "NaN", "-NaN", "",
                    ".", "+", "-", "e5", "1e", "1e+", ".e1", "+.5", "-.5e-3", "5.", "1__0", "1,5", "0x", "0xg", "-0x1",
                    "0b2", "0o8", "1 2", "--1", "+-1", "1e5e5", "0x1.8", "1n", "١٢٣", "1.2.3", "\u0000" };
                core = odd[r.nextInt(odd.length)];
                break;
            case 9:
                core = "1" + "0".repeat(r.nextInt(330)) + (r.nextBoolean() ? "" : "." + digits(r, r.nextInt(5)));
                break;
            case 10: {
                final StringBuilder b = new StringBuilder();
                final String alphabet = "0123456789.eE+-xXbBoOaAfF_ ";
                final int n = r.nextInt(14);
                for (int i = 0; i < n; i++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
                core = b.toString();
                break;
            }
            case 11:
            case 12: {
                // Halfway between two neighbouring doubles, written out: the read must pick the even one.
                double a = Math.abs(number(r));
                while (!Double.isFinite(a) || a == Double.MAX_VALUE) a = Math.abs(number(r));
                final java.math.BigDecimal mid = new java.math.BigDecimal(a).add(new java.math.BigDecimal(Math.nextUp(a)))
                    .divide(java.math.BigDecimal.valueOf(2));
                core = r.nextBoolean() ? mid.toPlainString() : mid.toString();
                break;
            }
            default:
                core = Integer.toString(r.nextInt());
        }
        return ws + core + ws2;
    }

    private static Uuid mine(final UUID u) {
        return new Uuid(u.getMostSignificantBits(), u.getLeastSignificantBits());
    }

    private static UUID anyId(final Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> new UUID(r.nextLong() & ~0xf000L | 0x4000L,
                r.nextLong() & 0x3fffffffffffffffL | 0x8000000000000000L);
            case 1 -> new UUID(r.nextLong() & 0xffffL, r.nextLong() & 0xffffL); // small values: leading zeros
            case 2 -> new UUID(r.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE, r.nextBoolean() ? -1L : 0L);
            default -> new UUID(r.nextLong(), r.nextLong());
        };
    }

    private static String text(final Random r) {
        final StringBuilder b = new StringBuilder();
        final int n = r.nextInt(r.nextInt(8) == 0 ? 300 : 40);
        for (int i = 0; i < n; i++) {
            switch (r.nextInt(6)) {
                case 0 -> b.append((char) (0xa0 + r.nextInt(0x300))); // Latin and more
                case 1 -> b.append((char) (0x4e00 + r.nextInt(0x4000))); // CJK
                case 2 -> b.appendCodePoint(0x1f300 + r.nextInt(0x300)); // a surrogate pair
                case 3 -> b.append((char) r.nextInt(0x20));
                default -> b.append((char) (0x20 + r.nextInt(0x5f)));
            }
        }
        return b.toString();
    }

    private static String jdkParse(final String s) {
        try {
            return UUID.fromString(s)
                .toString();
        } catch (final IllegalArgumentException e) {
            return e.getClass()
                .getSimpleName();
        }
    }

    public static void main(final String[] args) throws IOException {
        final Path out = Path.of(args[0]);
        final long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261119L;
        final int scale = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        final Random r = new Random(seed);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.US_ASCII)) {
            for (int i = 0; i < 120_000 * scale; i++) {
                final double x = number(r);
                w.write("[\"of\",\"" + hex(x) + "\"," + q(NumberText.of(x)) + "," + q(NumberText.json(x)) + "]\n");
            }
            for (int i = 0; i < 60_000 * scale; i++) {
                final double x = number(r);
                final int digits = r.nextInt(10) == 0 ? r.nextInt(101) : r.nextInt(12);
                w.write("[\"fixed\",\"" + hex(x) + "\"," + digits + "," + q(NumberText.fixed(x, digits)) + "]\n");
            }
            for (int i = 0; i < 40; i++) {
                final int bad = r.nextBoolean() ? 101 + r.nextInt(50) : -1 - r.nextInt(50);
                w.write("[\"fixed\",\"" + hex(number(r)) + "\"," + bad + ",null]\n");
            }
            for (int i = 0; i < 80_000 * scale; i++) {
                final String t = numberText(r);
                w.write("[\"parse\"," + q(t) + ",\"" + hex(NumberText.parse(t)) + "\"]\n");
            }
            for (int i = 0; i < 40_000 * scale; i++) {
                final double x = r.nextInt(3) == 0 ? Math.floor(number(r)) + 0.5 * (r.nextBoolean() ? 1 : -1) : number(r);
                w.write("[\"round\",\"" + hex(x) + "\",\"" + hex(JsMath.round(x)) + "\"]\n");
            }
            for (int i = 0; i < 40_000 * scale; i++) {
                // Floats' own edges (the halfway between two floats, the float limits, whole numbers past 2^24) are
                // where a target that does not narrow goes wrong, so a share of the cases sits on them.
                final double x;
                switch (r.nextInt(4)) {
                    case 0:
                        x = number(r);
                        break;
                    case 1: {
                        final float f = Float.intBitsToFloat(r.nextInt());
                        x = (Float.isNaN(f) ? 1.5f : f) + (r.nextBoolean() ? Math.ulp(Math.abs(Float.isNaN(f) ? 1.5f : f)) / 2 : 0);
                        break;
                    }
                    case 2:
                        x = 16_777_216 + r.nextInt(64) + (r.nextBoolean() ? 0.5 : 0);
                        break;
                    default:
                        x = new double[] { 0.1, 0.9, 0.5, 1e-46, 1.4e-45, 3.4028235e38, 3.4028236e38, 1e39,
                            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0 }[r.nextInt(13)];
                }
                w.write("[\"f32\",\"" + hex(x) + "\",\"" + hex(Float32.round(x)) + "\"]\n");
            }
            for (int i = 0; i < 30_000 * scale; i++) {
                final UUID u = anyId(r);
                final Uuid v = mine(u);
                w.write("[\"id\",\"" + hex(u.getMostSignificantBits()) + "\",\"" + hex(u.getLeastSignificantBits())
                    + "\"," + q(u.toString()) + "," + u.hashCode() + "," + u.version() + "," + u.variant() + "]\n");
                if (!v.toString()
                    .equals(u.toString())) throw new IllegalStateException("core-base Uuid differs from the JDK: " + u);
            }
            for (int i = 0; i < 20_000 * scale; i++) {
                final UUID a = anyId(r);
                final UUID b = r.nextInt(8) == 0 ? a : anyId(r);
                w.write("[\"cmp\",\"" + hex(a.getMostSignificantBits()) + "\",\"" + hex(a.getLeastSignificantBits())
                    + "\",\"" + hex(b.getMostSignificantBits()) + "\",\"" + hex(b.getLeastSignificantBits()) + "\","
                    + Integer.signum(a.compareTo(b)) + "]\n");
            }
            for (int i = 0; i < 300 * scale; i++) {
                final int n = 2 + r.nextInt(120);
                final List<UUID> ids = new ArrayList<>();
                for (int j = 0; j < n; j++) ids.add(j > 0 && r.nextInt(6) == 0 ? ids.get(r.nextInt(j)) : anyId(r));
                final List<Integer> order = new ArrayList<>();
                for (int j = 0; j < n; j++) order.add(j);
                order.sort((x, y) -> ids.get(x)
                    .compareTo(ids.get(y)));
                final StringBuilder in = new StringBuilder();
                for (final UUID u : ids) {
                    if (in.length() > 0) in.append(',');
                    in.append(hex(u.getMostSignificantBits()))
                        .append(':')
                        .append(hex(u.getLeastSignificantBits()));
                }
                final StringBuilder o = new StringBuilder();
                for (final int j : order) {
                    if (o.length() > 0) o.append(',');
                    o.append(j);
                }
                w.write("[\"order\"," + q(in.toString()) + "," + q(o.toString()) + "]\n");
            }
            for (int i = 0; i < 10_000 * scale; i++) {
                final String t = text(r);
                w.write("[\"name\"," + q(t) + "," + q(UUID.nameUUIDFromBytes(t.getBytes(StandardCharsets.UTF_8))
                    .toString()) + "]\n");
            }
            final String alphabet = "0123456789abcdefABCDEFg-+ ";
            for (int i = 0; i < 40_000 * scale; i++) {
                final String t;
                if (i % 2 == 0) {
                    final StringBuilder b = new StringBuilder();
                    final int n = r.nextInt(42);
                    for (int j = 0; j < n; j++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
                    t = b.toString();
                } else {
                    // A real id with one character changed, or in capitals.
                    final char[] c = anyId(r).toString()
                        .toCharArray();
                    if (r.nextBoolean()) c[r.nextInt(36)] = alphabet.charAt(r.nextInt(alphabet.length()));
                    final String s = new String(c);
                    t = r.nextBoolean() ? s.toUpperCase(java.util.Locale.ROOT) : s;
                }
                w.write("[\"uparse\"," + q(t) + "," + q(jdkParse(t)) + "]\n");
            }
        }
    }
}
