package com.gtnhplanner.web;

import org.teavm.jso.JSExport;

/**
 * The entry class of the solver module: the same role as {@link WebCore} for the core module (TeaVM needs a main class),
 * with the one export every module answers, so the loader can tell a stale file. The solver's own functions are
 * {@code SolverExports}. A module compiled alone shares no state with the others, so it carries its own copy of
 * {@code core-base} (see README.md, "Modules").
 */
public final class WebSolver {

    private WebSolver() {}

    public static void main(final String[] args) {
        // Nothing to start: the host calls the exported functions after loading the module.
    }

    /** The export convention's version, the same number as {@link WebCore#webCoreExportVersion()}. */
    @JSExport
    public static int webCoreExportVersion() {
        return WebCore.EXPORT_VERSION;
    }
}
