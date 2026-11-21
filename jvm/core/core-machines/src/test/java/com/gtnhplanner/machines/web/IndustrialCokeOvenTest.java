package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.GameMath;

/**
 * industrial-coke-oven.test.ts, table side: issue #58's oven (Kanthal coils, heat-resistant casing, one slice) and the
 * brick Coke Oven under the legacy alias. The hatch migration, power report and overclock cases go through the solver
 * and are covered by the golden fixture.
 */
class IndustrialCokeOvenTest {

    private static MachineTable.Context ctx(final Map<String, Integer> tiers, final Map<String, Double> values) {
        return new MachineTable.Context(
            id -> tiers.getOrDefault(id, 0),
            id -> values.getOrDefault(id, 0.0),
            4,
            null,
            null,
            null,
            null,
            null);
    }

    @Test
    void runsSixteenParallelsAtTheKanthalDiscount() {
        // MTEIndustrialCokeOven: 16 base parallels, Kanthal modifier 0.98^2, normal overclocks.
        final MachineTable.Behaviour b = MachineTable.behaviour("Industrial Coke Oven");
        final MachineTable.Context c = ctx(
            Map.of("heatingCoil", 1, "cokeOvenCasing", 0),
            Map.of("cokeOvenSlices", 1.0));
        assertEquals(16, MachineTable.resolve(b.parallels, c, 1));
        assertEquals(Math.pow(0.98, 2), MachineTable.resolve(b.power, c, 1), 1e-12);
        assertEquals(MachineTable.normal(), MachineTable.overclockSpec(b, c));
    }

    @Test
    void keepsTheOldIndustrialMapLabelEquivalentWithoutGivingBrickOvensIndustrialSettings() {
        assertSame(MachineTable.behaviour("Industrial Coke Oven"), MachineTable.behaviour("Coke Oven"));
        // A brick oven has no slices control: one op at a time, and a saved voltage buys no overclocks.
        final MachineTable.Behaviour b = MachineTable.behaviour("Coke Oven");
        final MachineTable.Context brick = ctx(Map.of(), Map.of());
        assertEquals(1, MachineTable.resolve(b.parallels, brick, 1));
        assertEquals(MachineTable.none(), MachineTable.overclockSpec(b, brick));
    }

    private static JsonObject golden() throws Exception {
        try (Reader r = new InputStreamReader(
            Conformance.open("machines/coke-oven-discount.json"),
            StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    private static String hex(final double d) {
        final String h = Long.toHexString(Double.doubleToRawLongBits(d));
        return "0000000000000000".substring(h.length()) + h;
    }

    private static double discountAt(final String machine, final int coilTierIndex) {
        final MachineTable.Context c = ctx(Map.of("heatingCoil", coilTierIndex), Map.of("cokeOvenSlices", 1.0));
        return MachineTable.resolve(MachineTable.behaviour(machine).power, c, 1);
    }

    @Test
    void theCoilDiscountIsTheGamesPowIntAtEveryTierBitForBit() throws Exception {
        // MTEIndustrialCokeOven.getEuModifier: GTUtility.powInt(0.98, coil tier + 1); the file holds the game's doubles.
        final JsonArray tiers = golden().getAsJsonArray("cokeOvenDiscount");
        assertEquals(14, tiers.size());
        for (final JsonElement e : tiers) {
            final JsonArray row = e.getAsJsonArray();
            final int tier = row.get(0).getAsInt();
            assertEquals(row.get(1).getAsString(), hex(discountAt("Industrial Coke Oven", tier - 1)), "tier " + tier);
        }
    }

    @Test
    void aHugeTotalKeepsTheGamesLastEuPerTick() throws Exception {
        // OverclockCalculator: ceil(eut * parallels * modifier * 4^overclocks). Tier 8, 6029 EU/t, 167 parallels, 4^5:
        // pow's discount made it 877,142,830, the game's squared one gives 877,142,829.
        final JsonObject total = golden().getAsJsonObject("total");
        final double modifier = discountAt("Industrial Coke Oven", total.get("coilTier").getAsInt() - 1);
        final double eut = total.get("eut").getAsDouble() * total.get("parallels").getAsInt()
            * modifier
            * GameMath.powInt(4, total.get("overclocks").getAsInt());
        assertEquals(877_142_829L, total.get("euPerTick").getAsLong());
        assertEquals(877_142_829L, (long) Math.ceil(eut));
    }

    @Test
    void everyOtherCoilDiscountIsAlsoTakenWithPowInt() {
        // Each game class takes its discount with GTUtility.powInt (MTEMegaOilCracker, MTELargeFluidExtractor,
        // MTEIndustrialThermalCentrifuge, MTEMegaAlloyBlastSmelter), so each is its squared power at every tier.
        for (int t = 0; t < 14; t++) {
            assertEquals(hex(GameMath.powInt(0.9, t + 1)), hex(discountAt("Mega Oil Cracker", t)), "cracker " + t);
            assertEquals(
                hex(0.8 * GameMath.powInt(0.9, t)),
                hex(discountAt("Large Fluid Extractor", t)),
                "extractor " + t);
            assertEquals(
                hex(0.8 * GameMath.powInt(0.95, t)),
                hex(discountAt("Large Thermal Refinery", t)),
                "refinery " + t);
            // Voltage tier 4 here, no recipe tier: 0.95 per coil tier above it.
            assertEquals(
                hex(GameMath.powInt(0.95, Math.max(0, t + 1 - 4))),
                hex(discountAt("Mega Alloy Blast Smelter", t)),
                "blast smelter " + t);
        }
    }
}
