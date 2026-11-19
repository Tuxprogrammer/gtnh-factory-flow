package com.gtnhplanner.core.solver.lp;

/**
 * Where the solver kernel's linear programs come from. One engine serves every host: ojAlgo, compiled with the core
 * ({@link OjAlgoEngine}), so the mod and the website give the same answers. The seam exists so the engine's cost can be
 * worked on without touching the kernel, and so another engine could stand in for one host if ever it had to; installing
 * one changes answers, so nothing does today.
 */
public interface LpEngine {

    /**
     * A fresh, empty program. {@code safetyMillis} is a net against a model that never ends, never reached by a working
     * one: past it a solve stops and says so ({@link LinearProgram.Result#outOfTime()}).
     */
    LinearProgram newProgram(long safetyMillis);

    /** The engine every model is built with. */
    static LpEngine get() {
        return Holder.engine;
    }

    /** Installs the engine (once, at start-up, before any solve). */
    static void install(final LpEngine engine) {
        Holder.engine = engine;
    }

    /** The installed engine. */
    final class Holder {

        private static volatile LpEngine engine = OjAlgoEngine.INSTANCE;

        private Holder() {}
    }
}
