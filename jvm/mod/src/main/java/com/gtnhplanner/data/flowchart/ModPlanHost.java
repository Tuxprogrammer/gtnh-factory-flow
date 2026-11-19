package com.gtnhplanner.data.flowchart;

import javax.annotation.Nullable;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.core.model.PlanHost;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;

/**
 * The mod as the plan model's host: plans are kept in the plans file ({@link PlanAPI#save()}), undo is the slot's
 * history ({@link PlanAPI#recordEdit}), and the picked answer is the one the plans' summary keeps for all of them,
 * saved
 * in the plans file. Installed by {@code CoreHost}.
 */
public final class ModPlanHost implements PlanHost<Graph> {

    public static final ModPlanHost INSTANCE = new ModPlanHost();

    private ModPlanHost() {}

    public static void install() {
        PlanHost.install(INSTANCE);
    }

    @Override
    public void save() {
        PlanAPI.save();
    }

    @Override
    public void recordEdit(final Graph plan, final Runnable edit) {
        PlanAPI.recordEdit(plan, edit);
    }

    @Nullable
    @Override
    public ChoiceKey excessChoice(final Graph plan) {
        return Plan.getInstance()
            .getSummary()
            .getExcessChoice();
    }

    @Override
    public void setExcessChoice(final Graph plan, @Nullable final ChoiceKey choice) {
        Plan.getInstance()
            .getSummary()
            .setExcessChoice(choice);
    }
}
