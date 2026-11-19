package com.gtnhplanner.core.power;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsMath;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;

/**
 * What a turbine card really makes at the flow the solve feeds it (turbine-flow.ts). A turbine takes the fuel in its
 * hatch each tick (up to its cap) and loses efficiency the further that flow is from optimal (fluidIntoPower in
 * MTEXLTurbine* and MTELargeTurbine*), so its EU is not proportional to its flow. The solve bills a card linearly: x0.575
 * of a turbine set to optimal flow counts 0.575 of its optimal EU, but one turbine fed that steadily makes about 0.575 x
 * 0.575 of it. This works out the real figure for the cards to show; the solve and its totals stay linear.
 * <p>
 * The functions take the card's power source. Finding it (a recipe's power info, its source id) is the planning
 * cards' job.
 */
public final class TurbineFlow {

    /**
     * @param turbines  whole turbines sharing the card's flow
     * @param flow      the flow each one gets, in the card's own unit
     * @param setFlow   the flow the card is set to (its optimal, or a Custom flow), per turbine
     * @param optimal   the rotor's optimal flow for this fuel, per turbine
     * @param unit      "L/t" or "L/s"
     * @param euMade    EU/t all the turbines really make at {@code flow}
     * @param euCounted EU/t the plan counts for the card
     */
    public record Check(double turbines, double flow, double setFlow, double optimal, String unit, double euMade,
        double euCounted) {}

    private TurbineFlow() {}

    private static boolean hasFlowSetting(final PowerSource source) {
        for (final PowerSetting setting : source.settings()) if (setting.id()
            .equals("flowMode")) return true;
        return false;
    }

    /** Whole turbines a card's flow is shared by: an Exactly card's count, else what runs rounded up. */
    public static double turbinesFor(final double running, @Nullable final Double exactPin) {
        return exactPin != null && exactPin > 0 ? exactPin : Math.max(1, Math.ceil(running - 1e-9));
    }

    /** The cheap test the card shell's layout key uses: a turbine card whose turbines do not each run whole. */
    public static boolean mayTurbineFlow(@Nullable final PowerSource source, final double running,
        final double turbines) {
        if (source == null || !(running > 0) || Math.abs(running - turbines) <= 1e-6) return false;
        return hasFlowSetting(source);
    }

    /**
     * The check for a turbine card running {@code running} turbines' worth on {@code turbines} whole turbines, or null
     * when each turbine gets the flow the card is set to (or the card is not a turbine, or runs none).
     */
    @Nullable
    public static Check turbineFlowCheck(@Nullable final PowerSource source,
        @Nullable final Map<String, String> settings, final double running, final double turbines) {
        if (source == null || !(running > 0) || !(turbines >= 1) || !hasFlowSetting(source)) return null;
        // Plasma flows are litres a second on the card, steam and gas litres a tick (Turbines).
        boolean perSecondUnit = false;
        for (final PowerSetting setting : source.settings()) {
            if (setting instanceof final PowerSetting.Number number && number.id()
                .equals("customFlow") && "L/s".equals(number.unit())) perSecondUnit = true;
        }
        final String unit = perSecondUnit ? "L/s" : "L/t";
        final PowerModel set = source.compute(settings);
        final double setFlow = perUnit(unit, firstInput(set));
        if (!(setFlow > 0)) return null;
        // The game moves whole litres a tick; a steady feed averages out to the nearest.
        final double flow = Math.max(1, JsMath.round((setFlow * running) / turbines));
        if (flow == setFlow) return null;
        final double optimal = perUnit(unit, firstInput(source.compute(with(settings, "flowMode", "optimal", null))));
        final double each = source.compute(with(settings, "flowMode", "custom", NumberText.of(flow)))
            .euPerTick();
        final double euMade = each * turbines;
        final double euCounted = set.euPerTick() * running;
        if (Math.abs(euMade - euCounted) < 1) return null;
        return new Check(turbines, flow, setFlow, optimal, unit, euMade, euCounted);
    }

    private static double firstInput(final PowerModel model) {
        return model.inputs()
            .isEmpty() ? 0
                : model.inputs()
                    .get(0)
                    .perSecond();
    }

    private static double perUnit(final String unit, final double perSecond) {
        return "L/s".equals(unit) ? perSecond : perSecond / 20;
    }

    /** {...settings, flowMode, customFlow (when given)}. */
    private static Map<String, String> with(@Nullable final Map<String, String> settings, final String flowMode,
        final String modeValue, @Nullable final String customFlow) {
        final Map<String, String> out = settings == null ? new HashMap<>() : new HashMap<>(settings);
        out.put(flowMode, modeValue);
        if (customFlow != null) out.put("customFlow", customFlow);
        return out;
    }
}
