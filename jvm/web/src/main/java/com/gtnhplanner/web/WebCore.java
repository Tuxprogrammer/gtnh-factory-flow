package com.gtnhplanner.web;

import org.teavm.jso.JSExport;

/**
 * The entry class of the web build: TeaVM needs a main class, and this one holds the exports that are about the build
 * itself, not about a core module. Module exports live in {@code com.gtnhplanner.web.exports}, one class per core
 * module (see README.md for the convention). The main class's own exports are top-level functions, so their names
 * start with {@code webCore}.
 */
public final class WebCore {

    /** The export convention's version every module's {@code webCoreExportVersion} answers. */
    public static final int EXPORT_VERSION = 1;

    private WebCore() {}

    public static void main(final String[] args) {
        // Nothing to start: the host calls the exported functions after loading the module.
    }

    /** Tells the loader which build it got, so a stale file is noticed. Bump with a change to the export convention. */
    @JSExport
    public static int webCoreExportVersion() {
        return EXPORT_VERSION;
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

    /**
     * Returns its argument. The cost of a call that crosses the boundary with a string in each direction, which the
     * loader's browser probe measures (a JSON plan or board is megabytes of text).
     */
    @JSExport
    public static String webCoreEcho(final String text) {
        return text;
    }

    /** The length of the text in UTF-16 units: a string crosses into the core and only a number comes back. */
    @JSExport
    public static int webCoreLength(final String text) {
        return text.length();
    }

    /** The text repeated {@code times} times: a small argument goes in and a large string comes back. */
    @JSExport
    public static String webCoreRepeat(final String text, final int times) {
        final StringBuilder out = new StringBuilder(text.length() * Math.max(times, 0));
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }
}
