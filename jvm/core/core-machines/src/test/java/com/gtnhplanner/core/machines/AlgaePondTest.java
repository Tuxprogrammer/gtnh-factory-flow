package com.gtnhplanner.core.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.machines.web.Web;

/** The Algae Farm as the website's algae-pond.ts has it (MTEAlgaePond): the hatch picks the recipe, compost lifts it. */
class AlgaePondTest {

    private static Web.Recipe pond(final int tier) {
        final Web.Recipe r = new Web.Recipe();
        r.id = "algae:" + tier;
        r.name = "Algae Pond";
        r.machineType = AlgaePond.MACHINE_TYPE;
        r.minimumTier = "ULV";
        r.durationTicks = 1;
        r.eut = 0;
        r.specialValue = (double) tier;
        r.inputs = List.of();
        r.outputs = List.of();
        return r;
    }

    @Test
    void theRecipeCarriesTheDrawOfTheHatchThatGrowsIt() {
        // getRecipeEUt: V[tier] x 9 / 10 in long maths; an EV recipe on an EV hatch draws 1843 EU/t.
        final Web.Recipe ev = AlgaePond.enrich(pond(4));
        assertEquals("EV", ev.minimumTier);
        assertEquals(1843, ev.eut);
        assertEquals(1843, AlgaePond.enrich(ev).eut, "enriching twice changes nothing");
        // Tier 15 only grows with compost on a MAX hatch, so compost is its default and it carries the MAX draw.
        assertEquals("MAX", AlgaePond.enrich(pond(15)).minimumTier);
        assertEquals(1932735276, AlgaePond.enrich(pond(15)).eut);
    }

    @Test
    void compostGrowsTheRecipeOneTierAboveItsHatch() {
        final Web.Recipe iv = AlgaePond.enrich(pond(5));
        final Map<String, String> compost = Map.of(AlgaePond.COMPOST, "compost");
        assertEquals(4, AlgaePond.hatchTier(iv, compost));
        // compostForTier(EV) = min(64, 2^3) Heavy Compost a run, added as an input once.
        final Web.Recipe fed = AlgaePond.withInputs(iv, compost);
        assertEquals(1, fed.inputs.size());
        assertEquals(AlgaePond.HEAVY_COMPOST_ID, fed.inputs.get(0).id);
        assertEquals(8, fed.inputs.get(0).amount);
        assertEquals(1, AlgaePond.withInputs(fed, compost).inputs.size());
        // Drawn from the EV hatch that grows it: 1843 over the IV draw the recipe carries.
        assertEquals(1843.0 / 7372, AlgaePond.powerMultiplier(iv, compost), 1e-12);
        assertNull(AlgaePond.gateReason(iv, compost, 4));
        assertEquals(
            "Needs one EV energy hatch. With compost the pond grows the recipe one tier above its hatch.",
            AlgaePond.gateReason(iv, compost, 5));
    }

    @Test
    void compostRunsGrowWithTheHatchUpToAStack() {
        assertEquals(1, AlgaePond.compostPerRun(0));
        assertEquals(1, AlgaePond.compostPerRun(1));
        assertEquals(2, AlgaePond.compostPerRun(2));
        assertEquals(64, AlgaePond.compostPerRun(7));
        assertEquals(64, AlgaePond.compostPerRun(14));
    }
}
