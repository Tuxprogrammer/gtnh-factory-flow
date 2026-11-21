package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The website's bee and crop recognisers, word for word (passive-production.ts), and the readout leaving them out. */
class PassiveRecipesTest {

    private static Web.Recipe recipe(final String map, final String machineType) {
        final Web.Recipe r = new Web.Recipe();
        r.id = "r";
        r.name = "r";
        r.machineType = machineType;
        r.minimumTier = "LV";
        r.durationTicks = 100;
        r.eut = 16;
        r.inputs = new java.util.ArrayList<>();
        r.outputs = new java.util.ArrayList<>();
        if (map != null) {
            r.source = new Web.Source();
            r.source.recipeMap = map;
        }
        return r;
    }

    @Test
    void wordsAreNormalizeLabel() {
        assertEquals(Arrays.asList("bee", "products"), PassiveRecipes.words("  Bee Products Recipe Map "));
        assertEquals(Arrays.asList("ic2", "crops"), PassiveRecipes.words("IC2_Crops recipes"));
        // A word only drops whole: "mapping" stays, "recipe_map" is one word and is split, not dropped.
        assertEquals(Arrays.asList("mapping", "recipe", "map"), PassiveRecipes.words("Mapping recipe_map"));
        assertEquals(Arrays.asList("industrial", "apiary"), PassiveRecipes.words("Industrial-Apiary"));
    }

    @Test
    void recognisesBeesAndCrops() {
        assertTrue(PassiveRecipes.beeOrCrop(recipe("Bee Products", "Apiary")));
        assertTrue(PassiveRecipes.beeOrCrop(recipe(null, "Alveary")));
        assertTrue(PassiveRecipes.beeOrCrop(recipe(null, "Industrial Apiary")));
        assertTrue(PassiveRecipes.beeOrCrop(recipe("IC2 Crops", "Crop Manager")));
        assertTrue(PassiveRecipes.beeOrCrop(recipe("CropsNH", "Crop Farm")));
        assertFalse(PassiveRecipes.beeOrCrop(recipe("Tree Growth Simulator", "")));
        assertFalse(PassiveRecipes.beeOrCrop(recipe("Electric Blast Furnace", "Electric Blast Furnace")));
        assertFalse(PassiveRecipes.beeOrCrop(recipe("Beekeeper", "Bees")));
        // isIndustrialApiaryMachineType reads text, not words.
        assertTrue(PassiveRecipes.isIndustrialApiary("Industrial Apiaryx"));
    }

    @Test
    void cropsNhMetadataNamesACrop() {
        final Web.Recipe r = recipe("Planting", "Planter");
        final Map<String, Object> crops = new LinkedHashMap<>();
        crops.put("tier", 3.0);
        crops.put("growthPoints", "450");
        crops.put("dropChance", true);
        r.metadata = new LinkedHashMap<>();
        r.metadata.put("cropsNh", crops);
        assertTrue(PassiveRecipes.beeOrCrop(r));
        crops.put("dropChance", 0.0);
        assertFalse(PassiveRecipes.beeOrCrop(r));
    }

    @Test
    void theReadoutLeavesThemOut() {
        final Web.Node node = new Web.Node();
        node.overclockTier = "LV";
        assertFalse(NodeMath.covers(recipe("Bee Products", "Apiary"), node));
        assertTrue(NodeMath.covers(recipe("Bender", "Bender"), node));
    }
}
