package com.gtnhplanner.core.model.read;

import java.util.ArrayList;
import java.util.List;

/**
 * One crafting-grid recipe as the game holds it (an {@code IRecipe} of Forge's CraftingManager), read once by the
 * game-side reader (the mod's {@code com.gtnhplanner.read.crafting.CraftingReader}) for both hosts: the export run
 * writes it into the raw export, and the mod builds a crafting card's dataset record ({@link CraftingDatasetRecipe})
 * from it.
 *
 * <p>
 * The export run compiles this file with Java 8's library (Jabel): nothing newer, no annotations. Fields that may be
 * absent are null when absent.
 */
public final class CraftingRecord {

    /** {@code shaped}, {@code shapeless}, or the recipe's class name when the reader cannot tell. */
    public String type;
    /** The recipe's class ({@code net.minecraft.item.crafting.ShapedRecipes}, an AE2 or ProjectRed recipe ...). */
    public String className;
    /** A shaped recipe's grid: its inputs are packed {@code width} cells a row. 0 when the recipe has no grid. */
    public int width;
    public int height;
    /** The cells that hold something, in the recipe's own order, each with its place in it. */
    public final List<Slot> inputs = new ArrayList<Slot>();
    /** What one craft makes. */
    public GtRecipeRecord.Stack output;

    /** One grid cell: one item or fluid, an ore dictionary name, a list of items, or nothing that exists. */
    public static final class Slot {

        public static final String ITEM = "item", FLUID = "fluid", ORE_DICTIONARY = "oreDictionary",
            CHOICE = "choice", MISSING = "missing";

        /** {@link #ITEM}, {@link #FLUID}, {@link #ORE_DICTIONARY}, {@link #CHOICE} or {@link #MISSING}. */
        public String kind;
        /** The cell's place among the recipe's inputs (a shaped recipe's packed grid index). */
        public int slotIndex;
        /** An item or fluid cell's stack. */
        public GtRecipeRecord.Stack stack;
        /**
         * An ore dictionary cell's names: the one it asks for, or every name whose items are exactly the cell's list
         * (Forge hands a ShapedOreRecipe the list, not the name).
         */
        public List<String> oreNames;
        /**
         * A choice cell's items, in the recipe's order; an ore dictionary cell's: what its names hold (the ore
         * dictionary's lists, name by name, in the game's order).
         */
        public List<GtRecipeRecord.Stack> alternatives;
        /** A choice cell's id: {@code choice:} and a hash of its items. */
        public String choiceId;
    }
}
