package com.gtnhplanner.client;

/** What {@code /gtnhplanner} says in chat when it cannot do what it was asked: one plain line, never nothing. */
public final class DeliveryText {

    public static final String UNREACHABLE = "The planner site could not be reached. Try again in a moment.";
    public static final String UNKNOWN = "Nothing is stored under that id, or it has expired.";
    public static final String BUSY = "The planner site is busy. Try again in a minute.";
    public static final String PLAN_UNREADABLE = "That plan could not be read in this game.";
    public static final String CHANGE_UNREADABLE = "That change could not be read.";
    public static final String OTHER_PLAN = "This was made for another plan.";
    public static final String NO_PLAN = "No plan is open. Open the plan this was made for, then try again.";
    public static final String TOUR = "Leave the tour first, then try again.";

    private DeliveryText() {}

    /** The line for a site that said no: a gone or wrong id, too many tries, or the site's own words. */
    public static String refused(final int status, final String reason) {
        if (status == 404) return UNKNOWN;
        if (status == 429) return BUSY;
        return "The planner site said no: " + reason;
    }

    /** "Opened Platline as a new plan." */
    public static String opened(final String name) {
        return "Opened " + name + " as a new plan.";
    }
}
