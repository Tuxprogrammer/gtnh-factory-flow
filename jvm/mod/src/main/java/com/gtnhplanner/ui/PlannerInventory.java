package com.gtnhplanner.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;

import codechicken.nei.recipe.GuiRecipe;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Keeps the player's inventory in step with the server while the planner is open and after it closes.
 *
 * <p>
 * The planner is a GuiContainer only so NEI attaches its item list ({@link Planner#open}); its container is the
 * client's alone, and the server never hears of it. Vanilla's {@code GuiContainer.initGui} makes a screen's container
 * the player's open one, and the client puts a server slot update for the player's own window (id 0) into the open
 * container: into the planner's, which has no slots, so every change to the main inventory (slots 9 to 35) was lost
 * while it was open, and after it closed by any route that does not put the player's inventory back (Esc does not).
 * The hotbar has a path of its own and kept updating. A stale copy counted free slots wrong for Pattern the plan, and
 * in creative the client writes the slots it shows, so a slot shown empty could be filled over a real item.
 *
 * <p>
 * So the player's own inventory stays the open container whenever the planner is up, on every layout of it, and
 * again when the planner, or an NEI page over it, closes to a screen with no container of its own.
 */
public final class PlannerInventory {

    public static final PlannerInventory INSTANCE = new PlannerInventory();

    private PlannerInventory() {}

    @SubscribeEvent
    public void onInit(final GuiScreenEvent.InitGuiEvent.Post event) {
        if (Planner.isPlanner(event.gui)) restore();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onOpen(final GuiOpenEvent event) {
        if (event.isCanceled() || event.gui instanceof GuiContainer) return;
        final GuiScreen from = Minecraft.getMinecraft().currentScreen;
        if (Planner.isPlanner(from) || from instanceof final GuiRecipe<?> page && Planner.isPlanner(page.firstGui))
            restore();
    }

    private static void restore() {
        final EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) player.openContainer = player.inventoryContainer;
    }
}
