package com.gtnhplanner.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.core.view.ViewSettings;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * The player's planner settings (the top bar's gear and its unit keys): how the board looks zoomed out and shows its
 * numbers, the minimap, and the in-world views of linked machines. Kept in
 * {@code config/gtnhplanner-settings.properties}, so they carry across worlds.
 */
public final class PlannerSettings {

    /** Where the minimap sits on the screen. */
    public enum Corner {

        TOP_RIGHT("Top right"),
        TOP_LEFT("Top left"),
        BOTTOM_RIGHT("Bottom right"),
        BOTTOM_LEFT("Bottom left");

        public final String label;

        Corner(final String label) {
            this.label = label;
        }
    }

    /** A corner id of the settings schema ("top-right") as the corner. */
    private static Corner corner(final String id) {
        return Corner.valueOf(
            id.toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_'));
    }

    private static final Corner MINIMAP_CORNER_AT_FIRST = corner(
        ViewSettings.MINIMAP_CORNER_IDS[ViewSettings.MINIMAP_CORNER_DEFAULT]);
    private static final Corner PIN_LIST_CORNER_AT_FIRST = corner(
        ViewSettings.PIN_LIST_CORNER_IDS[ViewSettings.PIN_LIST_CORNER_DEFAULT]);

    private static float[] floats(final double[] values) {
        final float[] out = new float[values.length];
        for (int i = 0; i < out.length; i++) out[i] = (float) values[i];
        return out;
    }

    /** The minimap's sizes, in GUI pixels. */
    public static final int[] MINIMAP_SIZES = ViewSettings.MINIMAP_SIZE_VALUES;
    public static final String[] MINIMAP_SIZE_NAMES = ViewSettings.MINIMAP_SIZE_LABELS;
    /** The minimap's zoom steps (world units per screen pixel inverted: board pixels drawn per screen pixel). */
    /** The minimap's zoom steps: past the board's glance zoom (0.45) it draws whole cards, as the board does. */
    public static final float[] MINIMAP_ZOOMS = floats(ViewSettings.MINIMAP_ZOOM_VALUES);
    /** How far away linked machines show their AR panels, in blocks. */
    public static final int[] AR_RANGES = ViewSettings.AR_RANGE_VALUES;
    /** The planner's sounds against the vanilla click's loudness (Normal matches it). */
    public static final String[] SOUND_NAMES = ViewSettings.SOUND_LEVEL_LABELS;
    public static final float[] SOUND_GAINS = floats(ViewSettings.SOUND_LEVEL_VALUES);

    private static Properties props;

    private PlannerSettings() {}

    // region The settings

    /** Whether the board has offered the tour yet (once, the first time it opens). */
    public static boolean tourOffered() {
        return bool(ViewSettings.TOUR_OFFERED_KEY, ViewSettings.TOUR_OFFERED_DEFAULT);
    }

    public static void setTourOffered(final boolean offered) {
        set(ViewSettings.TOUR_OFFERED_KEY, offered);
    }

    /** How a task board shows on this computer, by its plan id: "chain" for its chains, else its columns. */
    public static String taskView(final String planId) {
        return load().getProperty(
            ViewSettings.TASK_VIEW_KEY + planId,
            ViewSettings.TASK_VIEW_IDS[ViewSettings.TASK_VIEW_DEFAULT]);
    }

    public static void setTaskView(final String planId, final String view) {
        set(ViewSettings.TASK_VIEW_KEY + planId, view);
    }

    /** The newest release the player was told of (and answered), so it is said once; "" for none. */
    public static String updateOffered() {
        return load().getProperty(ViewSettings.UPDATE_OFFERED_KEY, ViewSettings.UPDATE_OFFERED_DEFAULT);
    }

    public static void setUpdateOffered(final String tag) {
        set(ViewSettings.UPDATE_OFFERED_KEY, tag);
    }

    public static boolean zoomedOutNames() {
        return bool(ViewSettings.ZOOMED_OUT_NAMES_KEY, ViewSettings.ZOOMED_OUT_NAMES_DEFAULT);
    }

    public static void setZoomedOutNames(final boolean on) {
        set(ViewSettings.ZOOMED_OUT_NAMES_KEY, on);
    }

    /**
     * "Problem outlines": a card whose bar shows a problem draws its edge in the bar's colour (on the board, zoomed
     * out,
     * on the minimap and over the world). On by default.
     */
    public static boolean problemOutlines() {
        return bool(ViewSettings.PROBLEM_OUTLINES_KEY, ViewSettings.PROBLEM_OUTLINES_DEFAULT);
    }

    public static void setProblemOutlines(final boolean on) {
        set(ViewSettings.PROBLEM_OUTLINES_KEY, on);
    }

    /**
     * "Zoomed-out view": the board zoom, in percent of a 1:1 view, below which cards turn to their glance faces
     * ({@code RecipeCard.glanceAt}); from {@value #ZOOMED_OUT_MIN} to {@value #ZOOMED_OUT_MAX} in steps of
     * {@value #ZOOMED_OUT_STEP}. Lower, a full card's lettering is unreadable before the glance face arrives.
     */
    public static final int ZOOMED_OUT_MIN = ViewSettings.ZOOMED_OUT_AT_MIN,
        ZOOMED_OUT_MAX = ViewSettings.ZOOMED_OUT_AT_MAX, ZOOMED_OUT_STEP = ViewSettings.ZOOMED_OUT_AT_STEP,
        ZOOMED_OUT_DEFAULT = ViewSettings.ZOOMED_OUT_AT_DEFAULT;

    /** Read on every card every frame: kept as the zoom, so no lookup on the way. */
    private static float zoomedOutAt = Float.NaN;

    public static int zoomedOutPercent() {
        final int at = integer(ViewSettings.ZOOMED_OUT_AT_KEY, ZOOMED_OUT_DEFAULT);
        return Math.max(ZOOMED_OUT_MIN, Math.min(ZOOMED_OUT_MAX, at));
    }

    public static void setZoomedOutPercent(final int percent) {
        final int stepped = Math.round(percent / (float) ZOOMED_OUT_STEP) * ZOOMED_OUT_STEP;
        set(ViewSettings.ZOOMED_OUT_AT_KEY, Math.max(ZOOMED_OUT_MIN, Math.min(ZOOMED_OUT_MAX, stepped)));
        zoomedOutAt = Float.NaN;
    }

    /** The zoom the glance view starts below. */
    public static float zoomedOutAt() {
        float at = zoomedOutAt;
        if (Float.isNaN(at)) zoomedOutAt = at = zoomedOutPercent() / 100f;
        return at;
    }

    /**
     * "Scroll changes values": the wheel over a count, chip, rate box, rule key, drawer, overview line or settings row
     * steps it. Off, it never changes a value: over the board it zooms, over a list it scrolls. On by default.
     */
    public static boolean scrollChangesValues() {
        Boolean on = scrollChangesValues;
        if (on == null) scrollChangesValues = on = bool(
            ViewSettings.SCROLL_CHANGES_VALUES_KEY,
            ViewSettings.SCROLL_CHANGES_VALUES_DEFAULT);
        return on;
    }

    public static void setScrollChangesValues(final boolean on) {
        set(ViewSettings.SCROLL_CHANGES_VALUES_KEY, on);
        scrollChangesValues = on;
    }

    /** Read on every wheel and every tip: kept, as the zoom is. */
    private static Boolean scrollChangesValues;

    public static boolean minimap() {
        return bool(ViewSettings.MINIMAP_SHOW_KEY, ViewSettings.MINIMAP_SHOW_DEFAULT);
    }

    public static void setMinimap(final boolean on) {
        set(ViewSettings.MINIMAP_SHOW_KEY, on);
    }

    public static int minimapSizeIndex() {
        return clamp(integer(ViewSettings.MINIMAP_SIZE_KEY, ViewSettings.MINIMAP_SIZE_DEFAULT), MINIMAP_SIZES.length);
    }

    public static void setMinimapSizeIndex(final int i) {
        set(ViewSettings.MINIMAP_SIZE_KEY, clamp(i, MINIMAP_SIZES.length));
    }

    public static int minimapSize() {
        return MINIMAP_SIZES[minimapSizeIndex()];
    }

    /** How see-through the minimap is drawn, in percent, in steps of {@link #MINIMAP_SEE_THROUGH_STEP} up to 80. */
    public static final int MINIMAP_SEE_THROUGH_STEP = ViewSettings.MINIMAP_SEE_THROUGH_STEP,
        MINIMAP_SEE_THROUGH_MAX = ViewSettings.MINIMAP_SEE_THROUGH_MAX;

    public static int minimapSeeThrough() {
        return Math.max(
            0,
            Math.min(
                MINIMAP_SEE_THROUGH_MAX,
                integer(ViewSettings.MINIMAP_SEE_THROUGH_KEY, ViewSettings.MINIMAP_SEE_THROUGH_DEFAULT)));
    }

    public static void setMinimapSeeThrough(final int percent) {
        set(ViewSettings.MINIMAP_SEE_THROUGH_KEY, Math.max(0, Math.min(MINIMAP_SEE_THROUGH_MAX, percent)));
    }

    public static boolean minimapCircle() {
        return bool(ViewSettings.MINIMAP_CIRCLE_KEY, ViewSettings.MINIMAP_CIRCLE_DEFAULT);
    }

    public static void setMinimapCircle(final boolean circle) {
        set(ViewSettings.MINIMAP_CIRCLE_KEY, circle);
    }

    public static Corner minimapCorner() {
        try {
            return Corner.valueOf(load().getProperty(ViewSettings.MINIMAP_CORNER_KEY, MINIMAP_CORNER_AT_FIRST.name()));
        } catch (final IllegalArgumentException e) {
            // A value that is not a corner is the default, as on the website.
            return MINIMAP_CORNER_AT_FIRST;
        }
    }

    public static void setMinimapCorner(final Corner corner) {
        load().setProperty(ViewSettings.MINIMAP_CORNER_KEY, corner.name());
        save();
    }

    public static int minimapZoomIndex() {
        return clamp(integer(ViewSettings.MINIMAP_ZOOM_KEY, ViewSettings.MINIMAP_ZOOM_DEFAULT), MINIMAP_ZOOMS.length);
    }

    public static void setMinimapZoomIndex(final int i) {
        set(ViewSettings.MINIMAP_ZOOM_KEY, clamp(i, MINIMAP_ZOOMS.length));
    }

    /** The minimap centres on the card of the linked machine under the crosshair. */
    public static boolean minimapFollows() {
        return bool(ViewSettings.MINIMAP_FOLLOW_KEY, ViewSettings.MINIMAP_FOLLOW_DEFAULT);
    }

    public static void setMinimapFollows(final boolean on) {
        set(ViewSettings.MINIMAP_FOLLOW_KEY, on);
    }

    /** The pin list: the plan's pinned machines on screen while playing. */
    public static boolean pinList() {
        return bool(ViewSettings.PIN_LIST_KEY, ViewSettings.PIN_LIST_DEFAULT);
    }

    public static void setPinList(final boolean on) {
        set(ViewSettings.PIN_LIST_KEY, on);
    }

    public static Corner pinListCorner() {
        try {
            return Corner
                .valueOf(load().getProperty(ViewSettings.PIN_LIST_CORNER_KEY, PIN_LIST_CORNER_AT_FIRST.name()));
        } catch (final IllegalArgumentException e) {
            return PIN_LIST_CORNER_AT_FIRST;
        }
    }

    public static void setPinListCorner(final Corner corner) {
        load().setProperty(ViewSettings.PIN_LIST_CORNER_KEY, corner.name());
        save();
    }

    /** The plan overlaid on the world: its placed cards over their blocks, with their wires. */
    public static boolean arLens() {
        return bool(ViewSettings.AR_LENS_KEY, ViewSettings.AR_LENS_DEFAULT);
    }

    public static void setArLens(final boolean on) {
        set(ViewSettings.AR_LENS_KEY, on);
    }

    /** Focus: the plan over the world shows a machine's card above it while you look at its ghost. */
    public static boolean arFocus() {
        return bool(ViewSettings.AR_FOCUS_KEY, ViewSettings.AR_FOCUS_DEFAULT);
    }

    public static void setArFocus(final boolean on) {
        set(ViewSettings.AR_FOCUS_KEY, on);
    }

    /** Labels: each machine of the plan over the world shows its name, tier, power and count in its ghost. */
    public static boolean arLabels() {
        return bool(ViewSettings.AR_LABELS_KEY, ViewSettings.AR_LABELS_DEFAULT);
    }

    public static void setArLabels(final boolean on) {
        set(ViewSettings.AR_LABELS_KEY, on);
    }

    /** What each wire over the world says it carries: on every wire, on the wires of the machine looked at, or none. */
    public static final String[] AR_WIRE_LABEL_NAMES = ViewSettings.AR_WIRE_LABELS_LABELS;

    public static final int WIRE_LABELS_ALWAYS = 0, WIRE_LABELS_LOOKED = 1, WIRE_LABELS_OFF = 2;

    public static int arWireLabels() {
        return clamp(
            integer(ViewSettings.AR_WIRE_LABELS_KEY, ViewSettings.AR_WIRE_LABELS_DEFAULT),
            AR_WIRE_LABEL_NAMES.length);
    }

    public static void setArWireLabels(final int i) {
        set(ViewSettings.AR_WIRE_LABELS_KEY, clamp(i, AR_WIRE_LABEL_NAMES.length));
    }

    /**
     * How placed machines show in the world: see-through ghosts, solid blocks, or not at all (wires and labels only).
     */
    public static final String[] AR_GHOST_NAMES = ViewSettings.AR_GHOSTS_LABELS;

    public static final int GHOSTS_SEE_THROUGH = 0, GHOSTS_SOLID = 1, GHOSTS_HIDDEN = 2;

    public static int arGhosts() {
        return clamp(integer(ViewSettings.AR_GHOSTS_KEY, ViewSettings.AR_GHOSTS_DEFAULT), AR_GHOST_NAMES.length);
    }

    public static void setArGhosts(final int i) {
        set(ViewSettings.AR_GHOSTS_KEY, clamp(i, AR_GHOST_NAMES.length));
    }

    /** The list of the world's keys in the corner, under the minimap. */
    public static boolean arKeys() {
        return bool(ViewSettings.AR_KEYS_KEY, ViewSettings.AR_KEYS_DEFAULT);
    }

    public static void setArKeys(final boolean on) {
        set(ViewSettings.AR_KEYS_KEY, on);
    }

    public static int arRangeIndex() {
        return clamp(integer(ViewSettings.AR_RANGE_KEY, ViewSettings.AR_RANGE_DEFAULT), AR_RANGES.length);
    }

    public static void setArRangeIndex(final int i) {
        set(ViewSettings.AR_RANGE_KEY, clamp(i, AR_RANGES.length));
    }

    public static int arRange() {
        return AR_RANGES[arRangeIndex()];
    }

    // endregion

    // region Task locations in the world

    /** What shows of a task's locations without Show being pressed: all, those within the distance, or none. */
    public static final String[] TASK_SHOW_NAMES = ViewSettings.TASK_SHOW_LABELS;

    public static final int TASK_ALWAYS = 0, TASK_NEAR = 1, TASK_NEVER = 2;
    /** How near is near, in blocks. */
    public static final int[] TASK_DISTANCES = ViewSettings.TASK_NEAR_VALUES;

    public static int taskShow() {
        return clamp(integer(ViewSettings.TASK_SHOW_KEY, ViewSettings.TASK_SHOW_DEFAULT), TASK_SHOW_NAMES.length);
    }

    public static void setTaskShow(final int i) {
        set(ViewSettings.TASK_SHOW_KEY, clamp(i, TASK_SHOW_NAMES.length));
    }

    public static int taskDistanceIndex() {
        return clamp(integer(ViewSettings.TASK_NEAR_KEY, ViewSettings.TASK_NEAR_DEFAULT), TASK_DISTANCES.length);
    }

    public static void setTaskDistanceIndex(final int i) {
        set(ViewSettings.TASK_NEAR_KEY, clamp(i, TASK_DISTANCES.length));
    }

    public static int taskDistance() {
        return TASK_DISTANCES[taskDistanceIndex()];
    }

    public static boolean taskBeam() {
        return bool(ViewSettings.TASK_BEAM_KEY, ViewSettings.TASK_BEAM_DEFAULT);
    }

    public static void setTaskBeam(final boolean on) {
        set(ViewSettings.TASK_BEAM_KEY, on);
    }

    public static boolean taskLabel() {
        return bool(ViewSettings.TASK_LABEL_KEY, ViewSettings.TASK_LABEL_DEFAULT);
    }

    public static void setTaskLabel(final boolean on) {
        set(ViewSettings.TASK_LABEL_KEY, on);
    }

    // endregion

    // region The way numbers are shown (the top bar's keys)

    /** The board's rate unit: per tick, second, minute or hour. */
    public static Fmt.RateUnit rateUnit() {
        try {
            return Fmt.RateUnit.valueOf(load().getProperty(ViewSettings.RATE_UNIT_KEY, Fmt.RateUnit.SECOND.name()));
        } catch (final IllegalArgumentException e) {
            return Fmt.RateUnit.SECOND;
        }
    }

    public static void setRateUnit(final Fmt.RateUnit unit) {
        set(ViewSettings.RATE_UNIT_KEY, unit.name());
    }

    /**
     * The rate key's fifth setting, EU: ports and the overview's inputs and outputs read the EU each unit cost. Rates
     * typed or wheeled, wires and drawers keep {@link #rateUnit()}.
     */
    public static boolean energyView() {
        return bool(ViewSettings.RATE_ENERGY_KEY, ViewSettings.RATE_ENERGY_DEFAULT);
    }

    public static void setEnergyView(final boolean on) {
        set(ViewSettings.RATE_ENERGY_KEY, Boolean.toString(on));
    }

    /** Power shown as amps at each machine's tier rather than EU/t. */
    public static boolean powerAsAmps() {
        return bool(ViewSettings.POWER_DISPLAY_KEY, ViewSettings.POWER_DISPLAY_DEFAULT == 1);
    }

    public static void setPowerAsAmps(final boolean amps) {
        set(ViewSettings.POWER_DISPLAY_KEY, amps);
    }

    /** Power summed with every machine running at once rather than on average. */
    public static boolean peakPower() {
        return bool(ViewSettings.POWER_SENSE_KEY, ViewSettings.POWER_SENSE_DEFAULT == 1);
    }

    public static void setPeakPower(final boolean peak) {
        set(ViewSettings.POWER_SENSE_KEY, peak);
    }

    // endregion

    // region The overview, as the player left it

    /** The overview's sections folded before the player folds any: Internal, the long tail. */
    public static final Set<String> OVERVIEW_FOLDED_AT_FIRST = Set.of(ViewSettings.OVERVIEW_FOLDED_DEFAULT);

    /** Whether the overview is open beside the board, or folded to a strip. */
    public static boolean overviewOpen() {
        return bool(ViewSettings.OVERVIEW_OPEN_KEY, ViewSettings.OVERVIEW_OPEN_DEFAULT);
    }

    public static void setOverviewOpen(final boolean open) {
        set(ViewSettings.OVERVIEW_OPEN_KEY, open);
    }

    /** The overview's width as the player dragged its edge, in board pixels ({@link RailFit} keeps it in range). */
    public static int overviewWidth() {
        return RailFit.clamp(integer(ViewSettings.OVERVIEW_WIDTH_KEY, RailFit.DEFAULT));
    }

    public static void setOverviewWidth(final int width) {
        set(ViewSettings.OVERVIEW_WIDTH_KEY, RailFit.clamp(width));
    }

    /** The overview's sections folded to their bars, by title (INPUTS, OUTPUTS, INTERNAL, MACHINES). */
    public static Set<String> overviewFolded() {
        final String saved = load().getProperty(ViewSettings.OVERVIEW_FOLDED_KEY);
        if (saved == null) return new TreeSet<>(OVERVIEW_FOLDED_AT_FIRST);
        final Set<String> out = new TreeSet<>();
        for (final String title : saved.split(",")) if (!title.isBlank()) out.add(title.trim());
        return out;
    }

    public static void setOverviewFolded(final Set<String> folded) {
        set(ViewSettings.OVERVIEW_FOLDED_KEY, String.join(",", new TreeSet<>(folded)));
    }

    // endregion

    // region Size

    /**
     * The planner's size against NEI's, as an index into these names. Normal is a GUI scale step below NEI's (never
     * below 2, where a step smaller is too small to read); Large is NEI's own; Small and Tiny are a step and two below
     * Normal.
     */
    public static final String[] SIZE_NAMES = ViewSettings.UI_SIZE_LABELS;
    public static final int SIZE_NORMAL = ViewSettings.UI_SIZE_DEFAULT,
        SIZE_LARGE = ViewSettings.UI_SIZE_IDS.length - 1;

    public static int size() {
        return clamp(integer(ViewSettings.UI_SIZE_KEY, ViewSettings.UI_SIZE_DEFAULT), SIZE_NAMES.length);
    }

    public static void setSize(final int index) {
        set(ViewSettings.UI_SIZE_KEY, clamp(index, SIZE_NAMES.length));
    }

    /** The planner's GUI scale on a game whose scale is {@code game}. */
    public static int boardScale(final int game) {
        final int size = size();
        if (size == SIZE_LARGE) return game;
        final int normal = Math.max(Math.min(game, 2), game - 1);
        return Math.max(1, normal - (SIZE_NORMAL - size));
    }

    // endregion

    // region Background

    /** The see-through slider's step, in percent, and where it starts. */
    public static final int SEE_THROUGH_STEP = ViewSettings.SEE_THROUGH_STEP,
        DEFAULT_SEE_THROUGH = ViewSettings.SEE_THROUGH_DEFAULT;
    /**
     * The old four steps (Solid, Mostly solid, Half, See-through), read once into the percent that replaced them;
     * See-through comes over as all the way, as it was meant to show the game behind.
     */
    private static final int[] OLD_SEE_THROUGH = { 0, 15, 40, 100 };

    /**
     * How see-through the board's and the overview's backgrounds are, in percent: 0 solid, 100 nothing but the game
     * behind the planner (its cards and text stay solid).
     */
    public static int seeThrough() {
        final int fallback = load().getProperty("board.background") == null ? DEFAULT_SEE_THROUGH
            : OLD_SEE_THROUGH[clamp(integer("board.background", 0), OLD_SEE_THROUGH.length)];
        return Math.max(0, Math.min(100, integer(ViewSettings.SEE_THROUGH_KEY, fallback)));
    }

    public static void setSeeThrough(final int percent) {
        load().remove("board.background");
        set(ViewSettings.SEE_THROUGH_KEY, Math.max(0, Math.min(100, percent)));
    }

    /** How solid the backgrounds are drawn, 0 to 1. */
    public static float backgroundAlpha() {
        return 1f - seeThrough() / 100f;
    }

    // endregion

    // region Sound

    public static int soundIndex() {
        return clamp(integer(ViewSettings.SOUND_LEVEL_KEY, ViewSettings.SOUND_LEVEL_DEFAULT), SOUND_GAINS.length);
    }

    public static void setSoundIndex(final int i) {
        set(ViewSettings.SOUND_LEVEL_KEY, clamp(i, SOUND_GAINS.length));
    }

    public static float soundGain() {
        return SOUND_GAINS[soundIndex()];
    }

    // endregion

    // region Storage

    private static boolean bool(final String key, final boolean fallback) {
        final String v = load().getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int integer(final String key, final int fallback) {
        try {
            return Integer.parseInt(load().getProperty(key, Integer.toString(fallback)));
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(final int i, final int length) {
        return Math.max(0, Math.min(length - 1, i));
    }

    private static void set(final String key, final Object value) {
        load().setProperty(key, String.valueOf(value));
        save();
    }

    /** The file a test keeps them in instead of the game's config folder; null in the game. */
    private static File testFile;

    /** Keeps them in {@code f} from now on and forgets what was read, as a game started afresh would (for tests). */
    static synchronized void useFile(final File f) {
        testFile = f;
        props = null;
        zoomedOutAt = Float.NaN;
        scrollChangesValues = null;
    }

    private static File file() {
        if (testFile != null) return testFile;
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-settings.properties");
    }

    private static synchronized Properties load() {
        if (props != null) return props;
        props = new Properties();
        final File f = file();
        if (!f.isFile()) return props;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not read {}", f, e);
        }
        return props;
    }

    private static synchronized void save() {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            props.store(out, "GTNH Planner settings");
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not write {}", f, e);
        }
    }

    // endregion
}
