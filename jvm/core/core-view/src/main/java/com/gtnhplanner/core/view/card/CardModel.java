package com.gtnhplanner.core.view.card;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerSource;

/**
 * Everything a recipe card knows about its machine, its ports and its solve, as data: names, icon keys, flags and
 * amounts, no game object. The card's view rules (its layout, chips, count, foot bar) read this and nothing of the
 * game, so they run on both hosts. Each host builds it from its plan, its recipe source and its solve: the mod from its
 * node, NEI and the balancer ({@code ui.card.CardModels}), the website (once the core builds its cards) from its
 * dataset and its solve.
 *
 * <p>
 * The fields are the union of what the two cards read today: the mod's {@code ui.card.CardModel} and the website's
 * {@code RailPort} with the facts {@code clean-model.ts} reads beside it. A field one side does not have yet says so;
 * that side fills it with the value that means "none".
 *
 * <p>
 * An icon key names a picture for the host that built the record: on the mod {@code item:<registry name>:<meta>}
 * (with a {@code #} suffix for a stack whose NBT changes its look), on the website its icon id. A view record carries
 * it through; only the host's renderer resolves it.
 *
 * @param id             the card's node
 * @param machineName    the machine as the card names it (the chosen machine's item, a crop's harvester, a bee's
 *                       species, a power source's name)
 * @param machineIcon    the machine's picture, null for none
 * @param machineChoices the machines that run the recipe, the picture's first (the mod's NEI catalysts, the website's
 *                       handlers); more than one means the card has a machine picker
 * @param gregtech       a GregTech recipe (tier, amps, overclocks)
 * @param steam          a GregTech steam machine: no voltage, so no tier
 * @param crop           a crop card: counts crops, its harvester is a setting
 * @param customRate     a custom rate card: always Exactly, both settings pinned
 * @param tier           the voltage tier the card runs at ("" for none)
 * @param amps           amps on the card's chip (1 when it has none)
 * @param multiblock     the machine is a multiblock
 * @param coilHeat       the coil's heat in K, 0 for none
 * @param usesHeat       the recipe asks heat of a coil
 * @param recipeHeat     the recipe's minimum coil heat, 0 for none
 * @param parallels      operations at once
 * @param inputs         input ports in recipe order
 * @param outputs        output ports in recipe order
 * @param durationTicks  one run, after settings and overclocks
 * @param euPerTick      one machine's draw, EU/t (a double: exact to 2^53, and a number in JavaScript)
 * @param machines       the solved machine count
 * @param pinned         the machine count is fixed by the player
 * @param circuitIcon    the programmed circuit's picture, null for none
 * @param circuitNumber  the number the circuit is dialled to, null for none
 * @param steamPerTick   steam one machine burns, L/t; 0 on anything but a steam machine
 * @param machineMath    the website's machine maths for a GregTech card (its run, parallels, why it would not
 *                       start); null on every other card
 * @param power          a non-recipe machine's source and readings; null on a recipe card
 */
public record CardModel(Uuid id, String machineName, @Nullable String machineIcon, List<String> machineChoices,
    boolean gregtech, boolean steam, boolean crop, boolean customRate, String tier, int amps, boolean multiblock,
    int coilHeat, boolean usesHeat, int recipeHeat, int parallels, List<Port> inputs, List<Port> outputs,
    int durationTicks, double euPerTick, double machines, boolean pinned, @Nullable String circuitIcon,
    @Nullable String circuitNumber, double steamPerTick, @Nullable NodeMath.Result machineMath,
    @Nullable Power power) {

    /** Below this (per second) an extra flow is solver dust, not a row. */
    public static final double EXTRA_EPS = 1e-6;

    public CardModel {
        machineChoices = List.copyOf(machineChoices);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    /**
     * One port as the card draws it.
     *
     * @param index         its place on its side, in recipe order
     * @param output        an output (else an input)
     * @param key           the resource it carries ({@code item:...}, {@code fluid:...}, {@link Energy#KEY}); "" for
     *                      none
     * @param name          the resource's display name
     * @param icon          its picture, null for none
     * @param fluid         a fluid
     * @param chance        the chance an output comes with, 1 for always
     * @param consumeChance the share of runs that use an input up, 1 for every run (0: never used up, as GregTech's
     *                      non-consumed inputs)
     * @param perSecond     the solved rate
     * @param wired         a wire or drawer is on it, or it needs none (nothing is used up)
     * @param extra         the part of {@code perSecond} the solve sends out of the plan (an output) or brings into it
     *                      (an input) here with no drawer: a row of its own under the port
     * @param free          free in the game (the Rock Breaker's lava): drawn, never wired, no rate. The mod sets it
     *                      from {@code FreeInputs.isFree} (core-model), the rule the rails' rows read
     * @param choice        more than one resource fills the slot (the dataset's alternatives); the mod's recipes have
     *                      one, so false there
     * @param setRate       the rate the player set on the port, per second; null for none
     */
    public record Port(int index, boolean output, String key, String name, @Nullable String icon, boolean fluid,
        double chance, double consumeChance, double perSecond, boolean wired, double extra, boolean free,
        boolean choice, @Nullable Double setRate) {

        /** What the port's wires carry: its rate less the extra. */
        public double wiredPerSecond() {
            return Math.max(0, perSecond - extra);
        }

        public boolean hasExtra() {
            return extra > EXTRA_EPS;
        }

        /** EU, a generator's output: drawn as a bolt and shown in EU/t. */
        public boolean isPower() {
            return Energy.KEY.equals(key);
        }
    }

    /**
     * A power card's source, its model at the card's settings, the stat lines to show (the model's own, then the flows
     * that could not be ports) and the settings as stored. {@code source} is null when the plan names a source this
     * build does not know; {@code sourceId} is the plan's name for it either way.
     */
    public record Power(String sourceId, @Nullable PowerSource source, @Nullable PowerModel model,
        List<PowerModel.Stat> stats, Map<String, String> settings) {

        public Power {
            stats = List.copyOf(stats);
        }

        /** EU/t one machine makes; 0 for a parasitic one. */
        public double madePerMachine() {
            return model == null ? 0 : Math.max(0, model.euPerTick());
        }
    }

    public boolean isPower() {
        return power != null;
    }
}
