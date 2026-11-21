package com.gtnhplanner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.client.IngredientColors;
import com.gtnhplanner.core.model.CannerRecipe;
import com.gtnhplanner.core.model.FluidContainers;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.ResourceRegistry;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.game.GameIds;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;

/**
 * The game as the core's {@link ResourceRegistry}: names, icons and colours from the item and fluid registries, ore
 * names from the ore dictionary, and containers from GregTech's Canner (Forge's container list put through GregTech's
 * own rule when GregTech is absent). Also where the mod's own resource keys ({@code item:<registry name>:<meta>},
 * {@code fluid:<name>}, as saved in picks, drawers and tanks) meet the shared ones. Client thread; nothing here runs
 * until a resource is asked about, so the headless tests never touch the registries through it.
 */
public final class GameResources implements ResourceRegistry {

    public static final GameResources INSTANCE = new GameResources();

    private GameResources() {}

    // ── the mod's keys and the shared ones ──

    /** The shared key of a mod key ({@link FfIds#resource}); null for a key of no kind the plan knows. */
    @Nullable
    public static ResourceKey of(@Nullable final String modKey) {
        return FfIds.resource(modKey);
    }

    /** The mod's key for a shared one, spelled as the game spells it; null when the game has no such resource. */
    @Nullable
    public static String modKey(final ResourceKey key) {
        return switch (key.kind()) {
            case POWER -> com.gtnhplanner.power.Energy.KEY;
            case FLUID -> {
                final FluidStack fluid = FluidRegistry.getFluidStack(key.id(), 1000);
                yield fluid == null ? null
                    : "fluid:" + fluid.getFluid()
                        .getName();
            }
            case ITEM -> {
                final ItemStack stack = stack(key);
                yield stack == null ? null : CardDefaults.itemKey(stack);
            }
            default -> null;
        };
    }

    private static final Map<ResourceKey, ItemStack> STACKS = new HashMap<>();

    /** A one-item stack of an item key (a copy: the caller owns it); null when the game has no such item. */
    @Nullable
    public static ItemStack stack(final ResourceKey key) {
        if (key.kind() != ResourceKey.Kind.ITEM) return null;
        ItemStack known;
        synchronized (STACKS) {
            known = STACKS.get(key);
        }
        if (known == null) {
            known = GameIds.stackOf(key.id());
            if (known == null) return null;
            known.stackSize = 1;
            synchronized (STACKS) {
                STACKS.put(key, known);
            }
        }
        return known.copy();
    }

    /** A fluid key with an amount; null when the game has no such fluid. */
    @Nullable
    public static FluidStack fluid(final ResourceKey key, final int litres) {
        if (key.kind() != ResourceKey.Kind.FLUID) return null;
        return FluidRegistry.getFluidStack(key.id(), litres);
    }

    // ── the registry ──

    @Override
    public String displayName(final ResourceKey key) {
        final String modKey = modKey(key);
        return modKey == null ? key.id() : Resources.name(modKey);
    }

    @Nullable
    @Override
    public String icon(final ResourceKey key) {
        return modKey(key);
    }

    @Override
    public int color(final ResourceKey key) {
        if (key.kind() == ResourceKey.Kind.FLUID) {
            final FluidStack fluid = fluid(key, 1000);
            return fluid == null ? -1 : IngredientColors.fluidColor(fluid);
        }
        final ItemStack stack = stack(key);
        return stack == null ? -1 : IngredientColors.itemColor(stack);
    }

    @Override
    public List<String> oreNames(final ResourceKey key) {
        final ItemStack stack = stack(key);
        if (stack == null) return List.of();
        final List<String> out = new ArrayList<>();
        try {
            for (final int ore : OreDictionary.getOreIDs(stack)) out.add(OreDictionary.getOreName(ore));
        } catch (final RuntimeException ignored) {
            // A stack the ore dictionary chokes on has no ore names.
        }
        return out;
    }

    @Override
    public List<ResourceKey> members(final ResourceKey oreDictionary) {
        final String name = oreDictionary.oreName();
        if (name == null) return List.of();
        final List<ResourceKey> out = new ArrayList<>();
        for (final String ore : OreDictionary.getOreNames()) {
            if (!ore.equalsIgnoreCase(name)) continue;
            for (final ItemStack s : OreDictionary.getOres(ore)) {
                if (s != null && s.getItem() != null) out.add(ResourceKey.item(GameIds.itemId(s)));
            }
        }
        return out;
    }

    @Nullable
    @Override
    public Port<?> port(final ResourceKey key, final double perCraft) {
        final int whole = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(perCraft)));
        final Port<?> port = switch (key.kind()) {
            case POWER -> new Port<>(RecipePropertyAPI.POWER, new com.gtnhplanner.power.Energy(perCraft), 1f);
            case FLUID -> {
                final FluidStack fluid = fluid(key, whole);
                yield fluid == null ? null : new Port<>(RecipePropertyAPI.FLUID, fluid, 1f);
            }
            case ITEM -> {
                final ItemStack item = stack(key);
                if (item == null) yield null;
                item.stackSize = whole;
                yield new Port<>(RecipePropertyAPI.ITEM, item, 1f);
            }
            default -> null;
        };
        if (port != null) port.setExactAmount(perCraft);
        return port;
    }

    private Map<ResourceKey, List<CannerRecipe>> canner;

    @Override
    public synchronized List<CannerRecipe> canner(final ResourceKey key) {
        // Nothing the game lacks is in a container: the Canner is read only for what it has (and never in a test).
        if (key.kind() == ResourceKey.Kind.ITEM ? stack(key) == null
            : key.kind() != ResourceKey.Kind.FLUID || FluidRegistry.getFluid(key.id()) == null) return List.of();
        if (canner == null) canner = index(Compat.GREGTECH.isLoaded ? GregTechCanner.read() : fromForge());
        return canner.getOrDefault(key, List.of());
    }

    private static Map<ResourceKey, List<CannerRecipe>> index(final List<CannerRecipe> recipes) {
        final Map<ResourceKey, List<CannerRecipe>> out = new LinkedHashMap<>();
        for (final CannerRecipe r : recipes) {
            out.computeIfAbsent(r.filled(), k -> new ArrayList<>())
                .add(r);
            out.computeIfAbsent(r.fluid(), k -> new ArrayList<>())
                .add(r);
            if (r.empty() != null && !r.empty()
                .equals(r.filled())) out.computeIfAbsent(r.empty(), k -> new ArrayList<>())
                    .add(r);
        }
        return out;
    }

    /** Forge's container list as GregTech turns it into Canner recipes, for a game without GregTech. */
    private static List<CannerRecipe> fromForge() {
        final List<FluidContainers.Container> list = new ArrayList<>();
        for (final FluidContainerRegistry.FluidContainerData d : FluidContainerRegistry
            .getRegisteredFluidContainerData()) {
            if (d == null || d.fluid == null
                || d.fluid.getFluid() == null
                || d.filledContainer == null
                || d.filledContainer.getItem() == null
                || d.emptyContainer == null
                || d.emptyContainer.getItem() == null) continue;
            list.add(
                new FluidContainers.Container(
                    item(d.filledContainer),
                    item(d.emptyContainer),
                    d.emptyContainer.stackSize > 0,
                    ResourceKey.fluid(GameIds.fluidId(d.fluid)),
                    d.fluid.amount));
        }
        return FluidContainers.fromContainers(list);
    }

    /** The shared key of a stack, remembering the stack it was read from (the registry finds items by key slowly). */
    static ResourceKey item(final ItemStack stack) {
        final ResourceKey key = ResourceKey.item(GameIds.itemId(stack));
        synchronized (STACKS) {
            if (!STACKS.containsKey(key)) {
                final ItemStack one = stack.copy();
                one.stackSize = 1;
                STACKS.put(key, one);
            }
        }
        return key;
    }

    /** GregTech's Canner map, read where GregTech is loaded (its classes load only then). */
    private static final class GregTechCanner {

        /**
         * Every Canner recipe that moves a fluid in or out of one container: one item in and one fluid in, one item
         * out (filling), or one item in, one fluid out, at most one item out (emptying); one of each container.
         */
        static List<CannerRecipe> read() {
            final List<CannerRecipe> out = new ArrayList<>();
            final Collection<gregtech.api.util.GTRecipe> all = gregtech.api.recipe.RecipeMaps.cannerRecipes
                .getAllRecipes();
            for (final gregtech.api.util.GTRecipe r : all) {
                final List<ItemStack> in = items(r.mInputs), made = items(r.mOutputs);
                final List<FluidStack> fin = fluids(r.mFluidInputs), fout = fluids(r.mFluidOutputs);
                if (in.size() != 1 || in.get(0).stackSize != 1) continue;
                if (fin.size() == 1 && fout.isEmpty() && made.size() == 1 && made.get(0).stackSize == 1) {
                    final FluidStack f = fin.get(0);
                    out.add(
                        new CannerRecipe(
                            item(made.get(0)),
                            item(in.get(0)),
                            ResourceKey.fluid(GameIds.fluidId(f)),
                            f.amount,
                            true));
                } else if (fout.size() == 1 && fin.isEmpty() && made.size() <= 1) {
                    final FluidStack f = fout.get(0);
                    if (made.size() == 1 && made.get(0).stackSize != 1) continue;
                    out.add(
                        new CannerRecipe(
                            item(in.get(0)),
                            made.isEmpty() ? null : item(made.get(0)),
                            ResourceKey.fluid(GameIds.fluidId(f)),
                            f.amount,
                            false));
                }
            }
            return out;
        }

        private static List<ItemStack> items(@Nullable final ItemStack[] stacks) {
            final List<ItemStack> out = new ArrayList<>();
            if (stacks != null) for (final ItemStack s : stacks) if (s != null && s.getItem() != null) out.add(s);
            return out;
        }

        private static List<FluidStack> fluids(@Nullable final FluidStack[] stacks) {
            final List<FluidStack> out = new ArrayList<>();
            if (stacks != null)
                for (final FluidStack s : stacks) if (s != null && s.getFluid() != null && s.amount > 0) out.add(s);
            return out;
        }
    }
}
