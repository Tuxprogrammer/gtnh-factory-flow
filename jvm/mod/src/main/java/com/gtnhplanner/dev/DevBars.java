package com.gtnhplanner.dev;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.gtnhplanner.ui.theme.Thumb;

/**
 * Every scroll bar as it was last drawn, for the harness's {@code /scrollbars}: where its track and thumb are, so a
 * script can press and drag it with the real mouse and see whether it moved. Dev games only.
 */
public final class DevBars {

    /**
     * A bar in its widget's pixels: {@code across} for one along the bottom; {@code cross} and {@code crossW} its
     * other axis.
     */
    public record Bar(IWidget widget, Thumb thumb, boolean across, int cross, int crossW, int scroll, long at) {}

    static final Map<String, Bar> BARS = new ConcurrentHashMap<>();

    /**
     * A slider in its widget's pixels: the mouse's {@code x0} to {@code x1} span the values from 0 to 1 in
     * {@code steps}; {@code y} its middle; {@code value} where it stands, {@code held} whether it is in hand.
     */
    public record Slider(IWidget widget, float x0, float x1, float y, double value, int steps, boolean held,
        long at) {}

    static final Map<String, Slider> SLIDERS = new ConcurrentHashMap<>();

    private DevBars() {}

    /** A bar drawn this frame (nothing outside a dev game). */
    public static void drawn(final String name, final IWidget widget, final Thumb thumb, final boolean across,
        final int cross, final int crossW, final int scroll) {
        if (!DevHarness.isEnabled()) return;
        BARS.put(name, new Bar(widget, thumb, across, cross, crossW, scroll, System.currentTimeMillis()));
    }

    /** A slider drawn this frame (nothing outside a dev game). */
    public static void slider(final String name, final IWidget widget, final float x0, final float x1, final float y,
        final double value, final int steps, final boolean held) {
        if (!DevHarness.isEnabled()) return;
        SLIDERS.put(name, new Slider(widget, x0, x1, y, value, steps, held, System.currentTimeMillis()));
    }
}
