package com.gtnhplanner.web;

import org.teavm.jso.JSExport;

/**
 * The entry class of the web build: TeaVM needs a main class, and this one holds the exports that are about the build
 * itself, not about a core module. Module exports live in {@code com.gtnhplanner.web.exports}, one class per core
 * module (see README.md for the convention).
 */
public final class WebCore {

    private WebCore() {}

    public static void main(final String[] args) {
        // Nothing to start: the host calls the exported functions after loading the module.
    }

    /** Tells the loader which build it got, so a stale file is noticed. Bump with a change to the export convention. */
    @JSExport
    public static int webCoreExportVersion() {
        return 1;
    }

    /**
     * What {@code Runtime.maxMemory()} answers in this build: the override in
     * {@code org.teavm.classlib.java.lang.TRuntime} makes it 4 GiB. The conformance run reads it back to prove the
     * override is the class that was compiled in (TeaVM's own TRuntime has no such method, so a lost override also
     * fails the compile).
     */
    @JSExport
    public static double webCoreRuntimeMaxMemory() {
        return Runtime.getRuntime()
            .maxMemory();
    }
}
