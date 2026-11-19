package com.gtnhplanner.core.view.group;

import java.util.List;

/**
 * What a collapsed group's summary card says: the machines to build, their EU/t, and per resource what goes in (more
 * used than made) and comes out (more made than used), busiest first. {@code rows} is what the card draws: the longer
 * list, one at least (the "Nothing goes in or out." line).
 */
public record GroupView(int machines, double euT, List<Flow> inputs, List<Flow> outputs, int rows) {

    /** One resource crossing the group's edge, a second (no sign). */
    public record Flow(String key, String name, double perSecond) {}
}
