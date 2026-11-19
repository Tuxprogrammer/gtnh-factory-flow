package com.gtnhplanner.machines;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.core.base.Float32Ops;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.machines.GameRates;

/**
 * A bee card as the website's bee tests read it (bee-goldens.jsonl.gz), for hosts that cannot call the model object by
 * object: JSON in with the card's settings and the pack's bee yield, JSON out with the housing the settings make, the
 * output multiplier a base chance gets for a product and for a specialty, and the Industrial Apiary's duration
 * multiplier. The bee is the goldens' own: pure-bred, one product and one specialty, the website's Normal lifespan.
 */
public final class BeeReadout {

    private BeeReadout() {}

    private static final Bees.Species SPECIES = new Bees.Species(
        "gregtech.bee.speciesPulsatingiron",
        "Pulsating Iron",
        0.6f,
        40,
        false,
        List.of(
            new Bees.Product("forestry:beecombs@7", "Parched Comb", 1, 0.3f, false),
            new Bees.Product("gregtech:gt.comb@18", "Pulsating Iron Comb", 1, 0.15f, true)));

    /**
     * The card {"housing":key,"tiers":{setting:value},"tier":voltage tier|absent,"beesYield":n|absent} as
     * {"housing":key,"skip":bool,"product":n,"specialty":n,"duration":n}. A card whose production term is nothing has
     * no bees to count on either side (a hair either side of 0 in floats and in doubles): it answers skip and no
     * multipliers.
     */
    public static String read(final String caseJson) {
        final Map<String, Object> c = JsonTree.object(JsonTree.parse(caseJson));
        final Map<String, Object> settings = new HashMap<>();
        final Map<String, Object> tiers = JsonTree.object(c.get("tiers"));
        for (final Map.Entry<String, Object> e : tiers.entrySet()) settings.put(e.getKey(), String.valueOf(e.getValue()));
        final String housing = JsonTree.str(c, "housing");
        settings.put(Bees.HOUSING, housing);
        final String tier = JsonTree.str(c, "tier");
        if (tier != null) settings.put(Bees.VOLTAGE, tier);
        final Double yield = JsonTree.boxed(c, "beesYield");
        final GameRates before = GameRates.active();
        GameRates.install(new GameRates(yield != null ? yield : 1, 1, 1));
        try {
            return JsonTree.write(card(settings));
        } finally {
            GameRates.install(before);
        }
    }

    private static Map<String, Object> card(final Map<String, Object> settings) {
        final Bees.Setup s = Bees.setup(settings, SPECIES);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("housing", s.housing().key());
        // A term of nothing (a Gentle and two Necrotic frames in an Apiary) is a hair either side of 0 in floats and in
        // doubles: no bees on either side, but not the same hair.
        if (s.housing() != Bees.MEGA_APIARY && Math.abs(Bees.term(s)) < 1e-5) {
            out.put("skip", true);
            return out;
        }
        out.put("skip", false);
        final Bees.Product product = SPECIES.products().get(0), special = SPECIES.products().get(1);
        final double productMultiplier, specialtyMultiplier;
        if (s.housing() == Bees.MEGA_APIARY) {
            productMultiplier = specialtyMultiplier = Bees.megaMultiplier(s);
        } else if (s.housing().industrial()) {
            // Per bee cycle, as the website counts it: the rolls uncapped, times the share of the acceleration the
            // queen's life allows (a short life stops at 100 ticks and makes up only whole multiples).
            final Bees.Run run = Bees.run(s, SPECIES);
            final double share = (double) run.divisor() * run.factor() / run.acceleration();
            final float p = Bees.productionModifier(s), t = s.housing().t(), speed = s.speed().value();
            // The sum and the quotient are floats on the JVM; the JavaScript target keeps floats as doubles.
            productMultiplier = s.climate().works()
                ? Float32Ops.div(
                    Float32Ops.add(
                        Bees.finalChance(product.chance(), speed, p, t),
                        Bees.finalChance(Float32Ops.div(product.chance(), 2f), speed, p, t)),
                    product.chance())
                    * share
                : 0;
            specialtyMultiplier = Bees.jubilant(s, SPECIES)
                ? Float32Ops.div(Bees.finalChance(special.chance(), speed, p, t), special.chance()) * share
                : 0;
        } else {
            final Map<String, Double> cycle = Bees.perCycle(s, SPECIES);
            productMultiplier = cycle.get(product.id()) / product.chance();
            specialtyMultiplier = cycle.get(special.id()) / special.chance();
        }
        out.put("product", productMultiplier);
        out.put("specialty", specialtyMultiplier);
        // The Industrial Apiary's acceleration: the website's duration multiplier is one over it.
        out.put("duration", s.housing().industrial() ? 1.0 / (1 << s.acceleration().speed()) : 1.0);
        return out;
    }
}
