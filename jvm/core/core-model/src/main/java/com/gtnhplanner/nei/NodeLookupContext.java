package com.gtnhplanner.nei;

import com.gtnhplanner.core.base.Uuid;

/**
 * The port the user last ran an NEI recipe/usage lookup from.
 * ItemStacks stay at the NEI boundary and wiring happens via {@code Port#canConnect}.
 */
public record NodeLookupContext(Uuid nodeId, boolean output, int portIndex) {}
