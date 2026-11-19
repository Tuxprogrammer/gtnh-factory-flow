package com.gtnhplanner.core.base;

/**
 * The host's seams as installed. One plain field each: the host installs them once at start-up, before any core code
 * runs, and core code only reads them (a host that starts threads starts them after installing).
 */
final class Installed {

    private Installed() {}

    static Log log = Log.STDERR;
    static CoreSettings settings = CoreSettings.DEFAULTS;
    static Text text = Text.KEYS;
}
