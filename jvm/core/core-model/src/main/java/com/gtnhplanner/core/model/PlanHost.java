package com.gtnhplanner.core.model;

import java.util.Objects;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;

/**
 * What the plan model needs from the host that holds its plans and is not data: keeping them (the mod's save file, the
 * website's design store), undo, and the state a host keeps beside a plan rather than in it. The model calls these;
 * each host installs its own once at start-up, before any plan is edited. Until then {@link #NONE}: plans live only in
 * memory, nothing is kept, and an edit is not an undo step.
 *
 * <p>
 * {@code P} is the host's plan type (the mod's {@code Graph}). One host, one plan type: {@link #get()} hands the
 * installed host back at the caller's type, unchecked.
 */
public interface PlanHost<P> {

    /**
     * The plans changed in a way the player means to keep: write them where this host keeps plans. The host decides
     * when the write happens (at once, or batched); the model never waits for it.
     */
    void save();

    /**
     * Runs {@code edit} on {@code plan} as one undo step: an undo puts back everything it changed at once, and an edit
     * that changed nothing leaves no step. The host owns the history.
     */
    void recordEdit(P plan, Runnable edit);

    /**
     * Which of the solve's equally good answers the player picked for {@code plan} ({@code balancer.Alternatives}), or
     * null for the solver's own. Kept by the host, not in the plan: the mod keeps one for all its plans, in its plans
     * file.
     */
    @Nullable
    ChoiceKey excessChoice(P plan);

    /** Sets the picked answer; null goes back to the solver's own. The caller marks the plan changed. */
    void setExcessChoice(P plan, @Nullable ChoiceKey choice);

    /** The installed host, at the caller's plan type. */
    @SuppressWarnings("unchecked")
    static <P> PlanHost<P> get() {
        return (PlanHost<P>) Holder.host;
    }

    /** Installs the host's own. */
    static void install(final PlanHost<?> host) {
        Holder.host = Objects.requireNonNull(host, "host");
    }

    /**
     * No host: nothing is saved, an edit just runs, and one picked answer is kept in memory for every plan (as the
     * mod keeps one for all of them).
     */
    PlanHost<Object> NONE = new PlanHost<>() {

        @Nullable
        private ChoiceKey choice;

        @Override
        public void save() {}

        @Override
        public void recordEdit(final Object plan, final Runnable edit) {
            edit.run();
        }

        @Nullable
        @Override
        public ChoiceKey excessChoice(final Object plan) {
            return choice;
        }

        @Override
        public void setExcessChoice(final Object plan, @Nullable final ChoiceKey choice) {
            this.choice = choice;
        }
    };

    /** The installed host: one plain field, set at start-up before any plan is edited. */
    final class Holder {

        private Holder() {}

        static PlanHost<?> host = NONE;
    }
}
