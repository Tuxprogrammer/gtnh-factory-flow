package com.gtnhplanner.core.layout.arrange;

/** Ink (boxes, zones, notes, arrows; {@code ArrangeInk} of board-arrange.ts): follows the cards it was written over. */
public record ArrangeInk(String id, double x, double y, double width, double height) {}
