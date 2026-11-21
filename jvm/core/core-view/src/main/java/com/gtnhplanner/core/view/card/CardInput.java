package com.gtnhplanner.core.view.card;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.RateUnit;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.data.flowchart.balancer.UpToReason;

/**
 * What a recipe card's view is made from, as plain data: the facts its machine's maths and its solve give (the
 * website's {@code useCleanCardModel} reads them from its dataset, machine maths and {@code PlanSolveExtras}; the mod
 * from its node, NEI and the balancer through {@link CardModel}), with the plan's read-outs cut to this card. No rule
 * of what the card shows is applied here: {@link CardViews#card} applies them all.
 *
 * <p>
 * A wire record: every component is a number, a flag, a string, an enum, a list, a string-keyed map or another record
 * of this file; an optional is null here and a missing key on the wire. Ids and port keys are the website's spelling
 * ({@code nodeId|i|handle}; a shared machine's recipe {@code node#rN}); the mod writes its own the same way.
 *
 * @param nodeId             the card's node
 * @param name               the card's name: its machine (the selected handler, or that machine's own item at the
 *                           card's tier)
 * @param hasPicker          more than one machine runs the recipe: the name is a menu
 * @param tier               the tier the card's tier ladder stands at; null when it has none
 * @param tierFixed          the tier cannot change (a fusion reactor's mark)
 * @param hatchControl       a multiblock on the curated machine maths takes its amps from a hatch chip
 * @param power              the machine's power report (EU machines); null for none
 * @param steam              a steam machine's report; null for none
 * @param parallel           operations at once by the machine's settings
 * @param structuralParallel what the structure allows at once
 * @param pinned             the settings pinned to the card's strip, in the gear sheet's order
 * @param art                the structure render; null for none
 * @param glanceArt          the zoomed-out view's picture: the render, else the machine's item
 * @param product            the recipe's first output, an item or a fluid with a picture: the zoomed-out corner
 * @param circuit            the programmed circuit; null for none
 * @param inputs             the input rows, in rail order
 * @param outputs            the output rows, in rail order
 * @param count              the count's facts
 * @param powerTier          a generator's own tier (the lowest whose one amp carries one machine), for its EU rows
 * @param upTo               this card's Up to read-out (only while its rule is Up to); null for none
 * @param forced             what the solve forces out of or into the plan at this card's rows, spills included
 * @param loop               the ring the card is in; null for none
 * @param leftovers          the plan's leftovers with a place on this card (for an idle card's trade)
 * @param turbine            a turbine's flow check; null for none
 * @param cardNames          names of the other cards the read-outs mention, by id
 * @param selected           the card is selected
 * @param menu               what the card's menu rows depend on
 * @param readOnly           the card is one the board only shows, from the other side's plan, and why; null for every
 *                           card of the board's own
 */
public record CardInput(String nodeId, String name, boolean hasPicker, @Nullable String tier, boolean tierFixed,
    boolean hatchControl, @Nullable PowerReport power, @Nullable SteamReport steam, double parallel,
    double structuralParallel, List<Setting> pinned, @Nullable String art, @Nullable String glanceArt,
    @Nullable Product product, @Nullable Circuit circuit, List<Port> inputs, List<Port> outputs, Count count,
    @Nullable String powerTier, @Nullable UpTo upTo, List<Forced> forced, @Nullable UpToLoop.Kind loop,
    List<Leftover> leftovers, @Nullable Turbine turbine, Map<String, String> cardNames, boolean selected,
    Menu menu, @Nullable ReadOnly readOnly) {

    /**
     * A card the board only shows (the mod's display-only card: a website card it cannot run as its own): why
     * ({@code off} switched off in the plan, {@code kind} a recipe kind the game has no page for, {@code missing} no such
     * recipe in this game, {@code absent} no recipe in the plan), and whether it takes part in the solve.
     */
    public record ReadOnly(String reason, boolean solved) {}

    /**
     * The board's display dials: the rate unit, the power dial ({@code eu}, {@code amps} or a tier name) and whether
     * power reads the average draw (else the peak).
     */
    public record Dials(RateUnit rate, String power, boolean averageDraw) {}

    /** One shared case: the dials and the card. */
    public record Case(Dials dials, CardInput card) {}

    /**
     * A machine's power report: whether it runs at its tier ({@code ok}), the tier, its amps, one machine's draw, what
     * runs at once by the supply, why it cannot run, a multiblock, and an exotic hatch's own chip ("16A").
     */
    public record PowerReport(boolean ok, String tier, double amps, double drawEuT, double parallels,
        @Nullable String gateReason, boolean multiblock, @Nullable String hatchChip) {}

    /** A steam machine: one machine's steam a tick, high pressure, what runs at once. */
    public record SteamReport(double drawSteamPerTick, boolean highPressure, double parallels) {}

    /** A setting pinned to the strip: its id, words, value as printed, its picture, and too low for the recipe. */
    public record Setting(String id, String label, String value, @Nullable String icon, boolean warn) {}

    /** A picture and whether it is a fluid's (a fluid's texture fills only the middle of its canvas). */
    public record Product(String icon, boolean fluid) {}

    /** The programmed circuit: its picture and the number it is dialled to. */
    public record Circuit(@Nullable String icon, @Nullable String number) {}

    /**
     * One row on a rail.
     *
     * @param key           the row's key (unique on the card)
     * @param handleId      the handle its wire docks on ({@code r<n>:} on a shared machine's recipe n)
     * @param output        an output row
     * @param name          the resource's name
     * @param kind          {@code item}, {@code fluid}, {@code power} or another kind
     * @param resourceId    the resource's id in its kind
     * @param perSecond     what the row carries now (an output's forced share included)
     * @param energyPerUnit EU spent per unit made; null when it has no books
     * @param wired         a wire or drawer is on it
     * @param chance        the chance an output comes with; null for always
     * @param consumeChance the share of runs that use an input up; null for every run
     * @param alternatives  how many resources fill the slot
     * @param free          free in the game (never wired, no rate)
     * @param setRate       the rate the player set on the row, per second; null for none
     */
    public record Port(String key, String handleId, boolean output, String name, String kind, String resourceId,
        double perSecond, @Nullable Double energyPerUnit, boolean wired, @Nullable Double chance,
        @Nullable Double consumeChance, int alternatives, boolean free, @Nullable Double setRate) {}

    /**
     * The count's facts: the rule, the count set (null for none), what the solve needs, how busy the solve keeps it
     * (null: fully), the node's parallel multiplier, a custom rate card (always Exactly), and whether nothing sets this
     * part of the plan.
     */
    public record Count(PinRule rule, @Nullable Double pin, double needed, @Nullable Double utilization,
        double nodeParallel, boolean customRate, boolean unset) {}

    /** An Up to card's read-out: the website's {@code PlanUpTo}. */
    public record UpTo(double capacity, double reached, @Nullable UpToReason reason, @Nullable String portKey,
        @Nullable String resourceKey, List<String> tied, @Nullable Double wants, List<String> upstream) {}

    /** A forced flow at one of the card's rows; a spill names the buffer it spills into. */
    public record Forced(String portKey, double perSecond, @Nullable String drawerId) {}

    /** A leftover's places: the website's {@code PlanLeftover.bearers}. */
    public record Leftover(List<Bearer> bearers) {}

    /** One place of a leftover: its card, side ({@code in}/{@code out}), share and resource. */
    public record Bearer(@Nullable String nodeId, @Nullable String side, double share, @Nullable String resourceKey) {}

    /**
     * A turbine card's flow check: whole turbines, the flow each gets and the set and optimal flows (in {@code unit}),
     * what they make and what the plan counts.
     */
    public record Turbine(int turbines, double flow, double setFlow, double optimal, String unit, double euMade,
        double euCounted) {}

    /**
     * The menu's facts: the board is calm or locked (read-only, checklist), the card's machine owns its recipe (it
     * cannot share), the card is a shared machine, a holder (custom rate, fluid tank) or an empty crop farm, task boards
     * exist (live plans), and the card is one the board only shows ({@link ReadOnly}: it can only be deleted).
     */
    public record Menu(boolean calm, boolean locked, boolean ownsRecipe, boolean shared, boolean holder,
        boolean emptyCropFarm, boolean tasks, boolean readOnly) {}
}
