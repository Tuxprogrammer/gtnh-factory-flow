package com.gtnhplanner.core.view.group;

import java.util.List;

import javax.annotation.Nullable;

/**
 * The members of a collapsed group (every card in it and in the groups nested in it), as a host worked them out from
 * the plan-wide solve; the core sums them into {@link GroupView}. Which cards count as machines, a pinned count, a
 * crop's seeds and a card's utilization are the host's reading of its plan; the sum, the rounding up and the order are
 * the core's.
 */
public record GroupInput(List<Member> members) {

    /**
     * One card. {@code host}: the machine it runs on, so a shared machine is one machine however many recipes are on it.
     * {@code builds}: the machines to build before rounding up, null when the card is not a machine (a drawer, a planning
     * card, a tank, a card that runs nothing). {@code euT}: its power as the overview counts it. {@code flows}: what it
     * uses (positive) and makes (negative), a second, per resource.
     */
    public record Member(String host, @Nullable Double builds, double euT, List<Flow> flows) {}

    /** {@code name} is what the resource is called, the first the group's members gave for the key. */
    public record Flow(String key, String name, double perSecond) {}
}
