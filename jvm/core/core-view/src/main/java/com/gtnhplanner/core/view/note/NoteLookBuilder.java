package com.gtnhplanner.core.view.note;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.Float32Ops;
import com.gtnhplanner.core.view.Json;

/**
 * A sticky note's colours from its colour tag: the paper is the tag's swatch a quarter of the way to white, the band,
 * fold and edge darker shades of the paper, the ink dark or light by how bright the paper is. Every mix is float32 with
 * half-up rounding per channel, as the website's {@code mixHex} does it.
 */
public final class NoteLookBuilder {

    private NoteLookBuilder() {}

    private static final String DEFAULT = "yellow";

    /** Every tag the app knows, with its swatch. */
    private static final Map<String, Integer> SWATCH = new LinkedHashMap<>();

    static {
        SWATCH.put("white", 0xF0F0F0);
        SWATCH.put("orange", 0xF9801D);
        SWATCH.put("magenta", 0xC74EBD);
        SWATCH.put("light_blue", 0x3AB3DA);
        SWATCH.put("yellow", 0xFED83D);
        SWATCH.put("lime", 0x80C71F);
        SWATCH.put("pink", 0xF38BAA);
        SWATCH.put("gray", 0x474F52);
        SWATCH.put("light_gray", 0x9D9D97);
        SWATCH.put("cyan", 0x169C9C);
        SWATCH.put("purple", 0x8932B8);
        SWATCH.put("blue", 0x3C44AA);
        SWATCH.put("brown", 0x835432);
        SWATCH.put("green", 0x5E7C16);
        SWATCH.put("red", 0xB02E26);
        SWATCH.put("black", 0x1D1D21);
        SWATCH.put("scarlet", 0xEF4444);
        SWATCH.put("amber", 0xE0A63A);
        SWATCH.put("emerald", 0x10B981);
        SWATCH.put("azure", 0x3B82F6);
        SWATCH.put("steel", 0x3C3E45);
        SWATCH.put("onyx", 0x303238);
    }

    public static NoteLookView build(final NoteLookInput in) {
        final int swatch = swatch(in.tag());
        final int paper = mix(0xFFFFFF, swatch, 0.25f);
        return new NoteLookView(
            hex(paper),
            hex(mix(0x000000, paper, 0.07f)),
            hex(mix(0x000000, paper, 0.2f)),
            hex(mix(0x000000, paper, 0.14f)),
            luminance(paper) < 0.34 ? "#F2F3F7" : "#1E1E22");
    }

    /** Whether the app knows the tag. */
    public static boolean knows(@Nullable final String tag) {
        return tag != null && SWATCH.containsKey(tag);
    }

    /** The tag's swatch as 0xRRGGBB; none, or an unknown tag, reads as yellow. */
    public static int swatch(@Nullable final String tag) {
        final Integer known = tag == null ? null : SWATCH.get(tag);
        return known != null ? known : SWATCH.get(DEFAULT);
    }

    public static String toJson(final NoteLookView v) {
        return new Json().begin()
            .str("paper", v.paper())
            .str("band", v.band())
            .str("fold", v.fold())
            .str("edge", v.edge())
            .str("ink", v.ink())
            .end()
            .toString();
    }

    /** {@code a} mixed into {@code b}, {@code share} of a, per channel rounded half up in float32. */
    static int mix(final int a, final int b, final float share) {
        final float rest = Float32Ops.sub(1f, share);
        return channel(a >> 16 & 0xFF, b >> 16 & 0xFF, share, rest) << 16
            | channel(a >> 8 & 0xFF, b >> 8 & 0xFF, share, rest) << 8
            | channel(a & 0xFF, b & 0xFF, share, rest);
    }

    private static int channel(final int a, final int b, final float share, final float rest) {
        return (int) ExactMath.round(
                Float32Ops.add(Float32Ops.mul(Float32Ops.of(a), share), Float32Ops.mul(Float32Ops.of(b), rest)));
    }

    private static String hex(final int rgb) {
        final String digits = "0123456789ABCDEF";
        final StringBuilder sb = new StringBuilder("#");
        for (int shift = 20; shift >= 0; shift -= 4) sb.append(digits.charAt(rgb >> shift & 0xF));
        return sb.toString();
    }

    /** Relative luminance (WCAG) of an RGB colour. */
    private static double luminance(final int rgb) {
        return 0.2126 * linear(rgb >> 16 & 0xFF) + 0.7152 * linear(rgb >> 8 & 0xFF) + 0.0722 * linear(rgb & 0xFF);
    }

    private static double linear(final int channel) {
        final double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : ExactMath.pow((c + 0.055) / 1.055, 2.4);
    }
}
