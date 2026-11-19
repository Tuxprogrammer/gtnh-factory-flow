package com.gtnhplanner.core.base;

import java.util.Objects;

/**
 * The core's logger. The host installs its own once at start-up, before the first core call (the mod: its log4j
 * logger); until then {@link #STDERR} is in place. Messages take log4j's {@code {}} placeholders, filled in order, and a
 * {@link Throwable} after the last placeholder's argument is logged with its stack trace. Log text is for people: a
 * number in it may be spelled however the host spells it, so nothing may parse, compare or hash it.
 */
public interface Log {

    void debug(String message, Object... args);

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    /** The installed logger. */
    static Log get() {
        return Installed.log;
    }

    /** Installs the host's logger. */
    static void install(final Log log) {
        Installed.log = Objects.requireNonNull(log, "log");
    }

    /** Info and above to standard error, debug dropped: what a core run gets with no host. */
    Log STDERR = new StandardLog();

    /** {@code message} with each {@code {}} replaced by the next argument; placeholders past the arguments stay. */
    static String fill(final String message, final Object... args) {
        return StandardLog.fill(message, args);
    }
}
