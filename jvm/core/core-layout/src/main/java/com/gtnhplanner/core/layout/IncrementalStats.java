package com.gtnhplanner.core.layout;

/** A solve's stats, and how much of the board it routed again: wires routed, wires kept, and whether afresh. */
public class IncrementalStats extends GridSolveStats {

    public int routed, kept;
    public boolean fresh;
}
