package com.gtnhplanner.core.layout.arrange;

/**
 * A card to place ({@code ArrangeCard} of the website's board-arrange.ts): its id, footprint, and where its top-left
 * stands today. {@code storage}: a drawer, tank or trash can (the website's role "storage"); false is a machine.
 */
public record ArrangeCard(String id, double x, double y, double width, double height, boolean storage) {}
