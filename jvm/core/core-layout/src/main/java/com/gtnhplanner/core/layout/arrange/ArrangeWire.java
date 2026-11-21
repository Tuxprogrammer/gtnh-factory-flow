package com.gtnhplanner.core.layout.arrange;

import javax.annotation.Nullable;

/**
 * A wire between two cards ({@code ArrangeWire} of board-arrange.ts): the source makes the resource, the target drinks
 * it. Absent fields are null, as the website's are undefined.
 *
 * @param id          the project edge behind it (null: it still places cards, it cannot be steered)
 * @param sourcePortY the port's centre below its card's top edge, when measured
 * @param targetPortY the same at the target
 * @param weight      how much it matters, any consistent scale (null: 1)
 * @param width       the stroke it routes at, px
 */
public record ArrangeWire(@Nullable String id, String source, String target, @Nullable Double sourcePortY,
    @Nullable Double targetPortY, @Nullable Double weight, @Nullable Double width) {}
