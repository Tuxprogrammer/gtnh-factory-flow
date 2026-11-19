/**
 * Crafting recipe classes whose ingredients exist only inside their own
 * `matches()` code, so the export cannot read them and gives the recipe no
 * inputs. Kept, such a recipe makes its output from nothing; a crafting recipe
 * with no inputs does not exist in the game, so these are dropped.
 *
 * The list names classes, never "any recipe without inputs": a class the
 * exporter can read (CraftingIngredients.java) must not be listed, and a new
 * unreadable class is added here by name after reading its source. Each entry
 * says what the game recipe really takes.
 */
export const UNREADABLE_CRAFTING_CLASSES = new Set([
  // Loads arrows into a quiver; any quiver, any arrows.
  "mods.battlegear2.recipies.QuiverRecipie2",
  // Takes the arrows out of any shield.
  "mods.battlegear2.recipies.ShieldRemoveArrowRecipie",
  // Repaints a locomotive with two of the 16 dyes DyeHelper lists.
  "mods.railcraft.common.carts.LocomotivePaintingRecipe",
  // Copies a gold routing ticket onto paper or a blank ticket.
  "mods.railcraft.common.util.crafting.RoutingTicketCopyRecipe",
  // Eight plates around an IC chip that carries a blueprint.
  "mrtjp.projectred.fabrication.FabricationRecipes$$anon$1",
  // Integer cells mean "a microblock of the material in the grid".
  "com.rwtema.extrautils.multipart.microblock.RecipeMicroBlocks",
  // A Division Sigil gives a random number of iron ingots.
  "com.rwtema.extrautils.crafting.RecipeUnsigil",
  // A door and five frame blocks of any one block.
  "net.malisis.doors.recipe.BigDoorRecipe",
  // Saws and combines microblocks of any material.
  "codechicken.microblock.MicroRecipe$",
  // Four stacks of exactly 64 paper.
  "pcl.openprinter.items.PrinterPaperRollRecipe",
  // Recolours an Ender Chest's frequency with dyes.
  "codechicken.enderstorage.common.EnderStorageRecipe",
  // Any of three Botania helmets plus Goggles of Revealing.
  "vazkii.botania.common.crafting.recipe.HelmRevealingRecipe",
  // Gene samples written onto a template.
  "net.bdew.gendustry.forestry.GeneRecipe",
  // Charges any powered Gendustry item with redstone; its display output is Fire.
  "net.bdew.gendustry.misc.ChargeRecipe",
  // Mates two butterflies.
  "forestry.lepidopterology.recipes.MatingRecipe",
  // A Remote Interface and a Wireless Transmitter bound to a player.
  "remoteio.common.recipe.RecipeRemoteInventory",
]);
