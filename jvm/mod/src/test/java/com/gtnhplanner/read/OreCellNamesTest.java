package com.gtnhplanner.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A multi-name ore dictionary cell is named by the name the game registered first, as the export always named it: a
 * plan stores the cell by that id. Driven by a stand-in dictionary: names in registration order (what
 * OreDictionary.getOreNames returns), each with its items as registry keys.
 */
class OreCellNamesTest {

    /** A stand-in ore dictionary: names in the order they were registered. */
    private static final class FakeDictionary {

        final Map<String, List<String>> items = new LinkedHashMap<String, List<String>>();

        FakeDictionary register(final String name, final String... keys) {
            List<String> list = items.get(name);
            if (list == null) {
                list = new ArrayList<String>();
                items.put(name, list);
            }
            list.addAll(Arrays.asList(keys));
            return this;
        }

        /** The names a cell holding exactly {@code keys} is, in the order the readers hand them to the dataset. */
        List<String> cell(final String... keys) {
            final Map<String, List<String>> bySignature = OreCellNames.bySignature(
                items.keySet()
                    .toArray(new String[0]),
                name -> signature(items.get(name)));
            return bySignature.get(signature(Arrays.asList(keys)));
        }

        /** The cell's id as the normalizer and CraftingDatasetRecipe write it: the first name. */
        String cellId(final String... keys) {
            final List<String> names = cell(keys);
            return names == null || names.isEmpty() ? null : "oredict:" + names.get(0);
        }

        private static String signature(final List<String> keys) {
            final List<String> sorted = new ArrayList<String>(keys);
            Collections.sort(sorted);
            return sorted.isEmpty() ? "" : sorted.toString();
        }
    }

    private static final String COPPER_ORE = "gregtech:gt.blockores@35",
        BASALT_COPPER_ORE = "gregtech:gt.blockores@4035", RAW_COPPER = "gregtech:gt.metaitem.03@35",
        QUICKSILVER = "thaumcraft:itemresource@3", INFUSED_AIR = "thaumcraft:itemshard@0";

    @Test
    void copperOreKeepsTheNameRegisteredFirst() {
        final FakeDictionary game = new FakeDictionary().register("oreCopper", COPPER_ORE)
            .register("oreBasaltCopper", BASALT_COPPER_ORE)
            .register("rawOreCopper", RAW_COPPER)
            .register("oreAnyCopper", COPPER_ORE)
            .register("oreBasaltAnyCopper", BASALT_COPPER_ORE)
            .register("rawOreAnyCopper", RAW_COPPER);
        assertEquals(Arrays.asList("oreCopper", "oreAnyCopper"), game.cell(COPPER_ORE));
        assertEquals("oredict:oreCopper", game.cellId(COPPER_ORE));
        assertEquals("oredict:oreBasaltCopper", game.cellId(BASALT_COPPER_ORE));
        assertEquals("oredict:rawOreCopper", game.cellId(RAW_COPPER));
    }

    @Test
    void theOrderIsTheGamesNotTheAlphabets() {
        // Registered the other way round, the other name names the cell: the game's order decides, nothing sorts it.
        final FakeDictionary game = new FakeDictionary().register("oreAnyCopper", COPPER_ORE)
            .register("oreCopper", COPPER_ORE)
            .register("quicksilver", QUICKSILVER)
            .register("gemMercury", QUICKSILVER);
        assertEquals("oredict:oreAnyCopper", game.cellId(COPPER_ORE));
        assertEquals(Arrays.asList("quicksilver", "gemMercury"), game.cell(QUICKSILVER));
        assertEquals("oredict:quicksilver", game.cellId(QUICKSILVER));
    }

    @Test
    void quicksilverKeepsItsNameOverGemMercury() {
        final FakeDictionary first = new FakeDictionary().register("quicksilver", QUICKSILVER)
            .register("gemMercury", QUICKSILVER);
        assertEquals("oredict:quicksilver", first.cellId(QUICKSILVER));
        final FakeDictionary second = new FakeDictionary().register("gemMercury", QUICKSILVER)
            .register("quicksilver", QUICKSILVER);
        assertEquals("oredict:gemMercury", second.cellId(QUICKSILVER));
    }

    @Test
    void anInfusedShardKeepsTheNameRegisteredFirst() {
        final FakeDictionary shardFirst = new FakeDictionary().register("shardAir", INFUSED_AIR)
            .register("gemInfusedAir", INFUSED_AIR);
        assertEquals(Arrays.asList("shardAir", "gemInfusedAir"), shardFirst.cell(INFUSED_AIR));
        assertEquals("oredict:shardAir", shardFirst.cellId(INFUSED_AIR));
        final FakeDictionary gemFirst = new FakeDictionary().register("gemInfusedAir", INFUSED_AIR)
            .register("shardAir", INFUSED_AIR);
        assertEquals("oredict:gemInfusedAir", gemFirst.cellId(INFUSED_AIR));
    }

    @Test
    void aListIsTheNamesHoldingExactlyItsItemsInAnyOrder() {
        final FakeDictionary game = new FakeDictionary()
            .register("plankWood", "minecraft:planks@0", "minecraft:planks@1")
            .register("plankOak", "minecraft:planks@0")
            .register("empty");
        // Item order in the list does not matter; a name holding more or fewer items is not this cell.
        assertEquals(Collections.singletonList("plankWood"), game.cell("minecraft:planks@1", "minecraft:planks@0"));
        assertEquals(Collections.singletonList("plankOak"), game.cell("minecraft:planks@0"));
        assertNull(game.cell("minecraft:planks@2"));
        // A name with no items names no cell.
        assertFalse(
            OreCellNames.bySignature(new String[] { "empty" }, name -> "")
                .containsKey(""));
    }
}
