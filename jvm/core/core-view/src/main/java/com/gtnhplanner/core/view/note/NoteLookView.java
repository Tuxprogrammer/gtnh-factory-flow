package com.gtnhplanner.core.view.note;

/**
 * A sticky note's colours as {@code #RRGGBB}: the paper, the glued band along its top, the folded corner, the faint edge
 * where it meets the board, and the ink for its text.
 */
public record NoteLookView(String paper, String band, String fold, String edge, String ink) {}
