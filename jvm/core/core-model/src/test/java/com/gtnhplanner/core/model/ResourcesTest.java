package com.gtnhplanner.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.Port;

/** Resources by key, the wiring rule, the container rule and fractional counts: the core alone, no host. */
class ResourcesTest {

    @Test
    void aKeyIsTheShapeThePlanJsonCarries() {
        final ResourceKey k = ResourceKey.parse("item:gregtech:gt.metaitem.01@2377");
        assertNotNull(k);
        assertEquals(ResourceKey.Kind.ITEM, k.kind());
        assertEquals("gregtech:gt.metaitem.01@2377", k.id());
        assertEquals("item:gregtech:gt.metaitem.01@2377", k.toString());
        assertEquals(ResourceKey.fluid("water"), ResourceKey.parse("fluid:water"));
        assertEquals(ResourceKey.EU, ResourceKey.parse("power:eu"));
        assertNull(ResourceKey.parse("stuff:x"), "no kind the plan knows");
        assertNull(ResourceKey.parse("water"));
        assertEquals("plankwood", ResourceKey.item("oredict:plankwood")
            .oreName());
        assertTrue(ResourceKey.item("minecraft:log@32767")
            .isWildcard());
        assertFalse(ResourceKey.fluid("x@32767")
            .isWildcard(), "only items have a damage");
        assertTrue(ResourceKey.item("b")
            .compareTo(ResourceKey.fluid("a")) < 0, "kind first");
    }

    /** Every case of conformance/model/wiring.json, which the website's resourceMatchesInput passes too. */
    @Test
    void wiringIsTheSharedRule() throws IOException {
        final Map<String, Object> doc = JsonTree.object(JsonTree.parse(Conformance.text("model/wiring.json")));
        int n = 0;
        for (final Object o : JsonTree.array(doc.get("cases"))) {
            final Map<String, Object> c = JsonTree.object(o);
            final List<ResourceKey> alternatives = new ArrayList<>();
            for (final Object a : JsonTree.array(c.get("alternatives"))) alternatives.add(ResourceKey.parse((String) a));
            final boolean takes = Wiring.accepts(
                ResourceKey.parse(JsonTree.str(c, "slot")),
                alternatives,
                ResourceKey.parse(JsonTree.str(c, "offered")));
            assertEquals(JsonTree.bool(c, "takes"), takes, JsonTree.str(c, "name"));
            n++;
        }
        assertTrue(n >= 20, "the file holds the cases");
    }

    /** A host's port value for the tests: a resource and an amount. */
    private static final class Held {

        final ResourceKey key;
        int amount;

        Held(final ResourceKey key, final int amount) {
            this.key = key;
            this.amount = amount;
        }
    }

    private static final PortType<Held> HELD = new PortType<>() {

        @Override
        public String getKey() {
            return "held";
        }

        @Override
        public ResourceKey resource(final Held value) {
            return value.key;
        }

        @Override
        public String key(final Held value) {
            return value.key.toString();
        }

        @Override
        public int extractAmount(final Held value) {
            return value.amount;
        }

        @Override
        public void setAmount(final Held value, final int amount) {
            value.amount = amount;
        }

        @Override
        public Held copy(final Held value) {
            return new Held(value.key, value.amount);
        }

        @Override
        public String formatDisplayName(final Held value) {
            return value.key.id();
        }

        @Override
        public String formatAmount(final float perSecond) {
            return "";
        }

        @Override
        public int color(final Held value) {
            return -1;
        }

        @Override
        public int getPinInputColor() {
            return 0x80FFFFFF;
        }

        @Override
        public int getPinOutputColor() {
            return 0xFFFFFFFF;
        }

        @Override
        public int getArrowColor() {
            return 0xFFFFFFFF;
        }

        @Override
        public boolean same(final Held a, final Held b) {
            return a.key.equals(b.key);
        }

        @Override
        public boolean folds(final Held a, final Held b) {
            return a.key.equals(b.key);
        }
    };

    private static Port<Held> port(final String key) {
        return new Port<>(HELD, new Held(ResourceKey.parse(key), 1), 1f);
    }

    @Test
    void aPortTakesWhatItsSlotLists() {
        final Port<Held> lapisDust = port("item:gregtech:gt.metaitem.01@2526"),
            lazuli = port("item:minecraft:dye@4");
        assertFalse(lazuli.feeds(lapisDust), "sharing an ore name is not enough");
        lapisDust.setAlternatives(
            List.of(
                new Held(ResourceKey.item("gregtech:gt.metaitem.01@2526"), 1),
                new Held(ResourceKey.item("minecraft:dye@4"), 1)));
        assertTrue(lazuli.feeds(lapisDust), "the slot lists it");
        final Port<?> picked = lapisDust.withAlternative(new Held(ResourceKey.item("minecraft:dye@4"), 1));
        assertEquals(ResourceKey.item("minecraft:dye@4"), picked.resource());
        assertTrue(port("item:gregtech:gt.metaitem.01@2526").feeds(picked), "set to one, it takes the others still");
        assertEquals(0x80FFFFFF, port("item:x").getPinColor(true), "no colour of its own: the kind's");
    }

    @Test
    void mergingAveragesTheChanceByAmount() {
        final Port<Held> a = port("item:a"), b = port("item:a");
        a.setChance(0.5f);
        b.getValue().amount = 3;
        a.merge(b);
        assertEquals(4, a.getAmount());
        assertEquals((1 * 0.5f + 3 * 1f) / 4, a.getChance());
    }

    /** A registry that knows only Canner recipes and names. */
    private static ResourceRegistry canner(final List<CannerRecipe> recipes, final Map<ResourceKey, String> names) {
        final Map<ResourceKey, List<CannerRecipe>> by = new HashMap<>();
        for (final CannerRecipe r : recipes) {
            by.computeIfAbsent(r.filled(), k -> new ArrayList<>())
                .add(r);
            by.computeIfAbsent(r.fluid(), k -> new ArrayList<>())
                .add(r);
            if (r.empty() != null) by.computeIfAbsent(r.empty(), k -> new ArrayList<>())
                .add(r);
        }
        return new ResourceRegistry() {

            @Override
            public String displayName(final ResourceKey key) {
                return names.getOrDefault(key, key.id());
            }

            @Override
            public String icon(final ResourceKey key) {
                return null;
            }

            @Override
            public int color(final ResourceKey key) {
                return -1;
            }

            @Override
            public List<String> oreNames(final ResourceKey key) {
                return List.of();
            }

            @Override
            public List<ResourceKey> members(final ResourceKey oreDictionary) {
                return List.of();
            }

            @Override
            public List<CannerRecipe> canner(final ResourceKey key) {
                return by.getOrDefault(key, List.of());
            }

            @Override
            public Port<?> port(final ResourceKey key, final double perCraft) {
                return null;
            }
        };
    }

    private static final ResourceKey CELL = ResourceKey.item("ic2:itemcellempty"),
        BUCKET = ResourceKey.item("minecraft:bucket"), CAPSULE = ResourceKey.item("forestry:waxcapsule"),
        WATER = ResourceKey.fluid("water"), WATER_CELL = ResourceKey.item("ic2:itemcellwater"),
        WATER_BUCKET = ResourceKey.item("minecraft:water_bucket"), WATER_CAPSULE = ResourceKey.item("forestry:waxcapsulewater"),
        PLATINUM = ResourceKey.fluid("molten.platinum"), PLATINUM_CELL = ResourceKey.item("gregtech:gt.metaitem.01@30085");

    @Test
    void gregTechsRuleFillsEveryContainerAndNeverEmptiesTheFive() {
        final List<CannerRecipe> recipes = FluidContainers.fromContainers(
            List.of(
                new FluidContainers.Container(WATER_BUCKET, BUCKET, true, WATER, 1000),
                new FluidContainers.Container(WATER_CELL, CELL, true, WATER, 1000),
                new FluidContainers.Container(PLATINUM_CELL, CELL, true, PLATINUM, 144),
                new FluidContainers.Container(ResourceKey.item("x:lava_clay"), ResourceKey.item("x:clay"), false,
                    ResourceKey.fluid("lava"), 1000),
                new FluidContainers.Container(ResourceKey.item("x:nothing"), CELL, true, WATER, 0)));
        assertEquals(7, recipes.size(), "a filling and an emptying recipe each, platinum fills only, empty skipped");
        final ResourceRegistry r = canner(recipes, Map.of());
        final FluidContainers.Pair water = FluidContainers.pair(WATER_CELL, true, r);
        assertEquals(new FluidContainers.Pair(WATER_CELL, WATER, 1000, CELL), water);
        assertNotNull(FluidContainers.pair(PLATINUM_CELL, false, r), "a platinum cell is filled");
        assertNull(FluidContainers.pair(PLATINUM_CELL, true, r), "and never emptied");
        assertNull(FluidContainers.pair(ResourceKey.item("x:lava_clay"), true, r), "an emptying that leaves no container");
        assertNull(FluidContainers.pair(CELL, true, r), "an empty cell holds nothing");
    }

    @Test
    void aTankTakesWhatTheCannerDoesThePlainCellFirst() {
        final ResourceRegistry r = canner(
            List.of(
                new CannerRecipe(WATER_BUCKET, BUCKET, WATER, 1000, true),
                new CannerRecipe(WATER_BUCKET, BUCKET, WATER, 1000, false),
                new CannerRecipe(WATER_CAPSULE, CAPSULE, WATER, 1000, true),
                new CannerRecipe(WATER_CAPSULE, CAPSULE, WATER, 1000, false),
                new CannerRecipe(WATER_CELL, CELL, WATER, 1000, true),
                new CannerRecipe(WATER_CELL, CELL, WATER, 1000, false),
                new CannerRecipe(PLATINUM_CELL, CELL, PLATINUM, 144, true)),
            Map.of(CAPSULE, "Wax Capsule", BUCKET, "Bucket", CELL, "Empty Cell"));
        final FluidContainers.Hold supply = FluidContainers.take(WATER, true, r);
        assertNotNull(supply);
        assertTrue(supply.emptying(), "water asked of it comes out of cells");
        assertEquals(WATER_CELL, supply.pair()
            .cell(), "the plain Empty Cell first");
        final FluidContainers.Hold given = FluidContainers.take(PLATINUM, false, r);
        assertNotNull(given, "molten platinum given to it goes into cells");
        assertFalse(given.emptying());
        assertEquals(144, given.pair()
            .litres());
        assertNull(FluidContainers.take(PLATINUM, true, r), "and never comes out of them");
        assertNull(FluidContainers.take(PLATINUM_CELL, false, r), "a platinum cell given to it is refused");
        final FluidContainers.Hold cellAsked = FluidContainers.take(PLATINUM_CELL, true, r);
        assertNotNull(cellAsked, "a platinum cell asked of it is filled");
        assertFalse(cellAsked.emptying());
        assertNull(FluidContainers.take(ResourceKey.item("minecraft:dirt"), true, r), "an item that holds nothing");

        final ResourceRegistry noPlain = canner(
            List.of(
                new CannerRecipe(WATER_BUCKET, BUCKET, WATER, 1000, false),
                new CannerRecipe(WATER_CAPSULE, CAPSULE, WATER, 1000, false)),
            Map.of(CAPSULE, "Wax Cell", BUCKET, "Bucket"));
        assertEquals(WATER_CAPSULE, FluidContainers.take(WATER, true, noPlain)
            .pair()
            .cell(), "then a container called a cell, then the first");
    }

    @Test
    void aSharedMachineKeepsAFractionalCountAndSavesAWholeOneAsBefore() {
        final MachineGroup g = new MachineGroup(Uuid.fromString("00000000-0000-0000-0000-000000000002"));
        PinRule.set(g, PinRule.UP_TO, 2.5);
        assertEquals(2.5, g.machineCount());
        assertEquals(3, g.getMachineCapacity(), "in whole machines, rounded up");
        PinRule.set(g, PinRule.UP_TO, 2);
        assertEquals(2, g.machineCount());
        g.setMachineCapacity(4);
        assertEquals(4, g.machineCount(), "a whole capacity lets go of the fraction");
    }
}
