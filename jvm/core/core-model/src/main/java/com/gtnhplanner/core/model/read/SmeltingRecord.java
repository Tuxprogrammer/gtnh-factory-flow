package com.gtnhplanner.core.model.read;

/**
 * One furnace recipe as the game holds it (vanilla {@code FurnaceRecipes}: an input stack and what it smelts into),
 * read once by the game-side reader (the mod's {@code com.gtnhplanner.read.furnace.SmeltingReader}) for both hosts:
 * the export run writes it into the raw export, and the mod builds a furnace card's dataset record
 * ({@link SmeltingDatasetRecipe}) from it.
 *
 * <p>
 * The export run compiles this file with Java 8's library (Jabel): nothing newer, no annotations. A slot is a
 * {@link GtRecipeRecord.Stack}, the one slot type the readers share.
 */
public final class SmeltingRecord {

    /** Ticks a vanilla furnace takes per item (TileEntityFurnace: furnaceCookTime reaches 200). */
    public static final int FURNACE_COOK_TICKS = 200;

    /** What goes in: one item, its damage as the recipe names it (32767 when any damage smelts). */
    public GtRecipeRecord.Stack input;
    /** What comes out, with the recipe's own stack size. */
    public GtRecipeRecord.Stack output;
}
