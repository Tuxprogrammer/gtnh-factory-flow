package com.gtnhplanner.core.layout;

/** Something wires route round: a card, a drawer or a board frame, by id, in board px. */
public record GridObstacle(String id, double left, double top, double right, double bottom) {}
