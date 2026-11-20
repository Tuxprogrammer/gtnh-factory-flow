package com.gtnhplanner.library;

import java.io.IOException;

/**
 * Thrown instead of a network call when no site is configured ({@link SiteConfig}): the feature is off, and this is
 * what the player is told. Nothing was sent.
 */
public final class SiteOff extends IOException {

    /** What the library, accounts, plan posting and cloud plans say when there is no site. */
    public static final String MESSAGE = "No community site is set up on this install.";

    public SiteOff() {
        super(MESSAGE);
    }
}
