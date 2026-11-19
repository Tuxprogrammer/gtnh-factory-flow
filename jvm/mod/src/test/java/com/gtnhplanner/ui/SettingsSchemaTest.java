package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.Config;
import com.gtnhplanner.core.view.ViewSettings;
import com.gtnhplanner.core.view.ViewSounds;

/**
 * The mod reads its keys, defaults and ranges from the settings schema (core-view/settings), and its sounds are the
 * sound table's. Changing the schema without the mod, or the mod without the schema, fails here.
 */
class SettingsSchemaTest {

    private static final Path SETTINGS = Paths.get("../core/core-view/settings");
    private static final Path SOURCE = Paths.get("src/main/java/com/gtnhplanner");

    @AfterEach
    void backToTheGame() {
        PlannerSettings.useFile(null);
    }

    private static JsonObject json(final String file) throws IOException {
        return new JsonParser().parse(Files.readString(SETTINGS.resolve(file), StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private static String upper(final String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toUpperCase();
    }

    @Test
    void everySettingThePropertiesFileHoldsIsReadThroughTheSchema() throws IOException {
        final String source = Files.readString(SOURCE.resolve("ui/PlannerSettings.java"), StandardCharsets.UTF_8);
        final List<String> missing = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> e : json("settings.json").getAsJsonObject("settings")
            .entrySet()) {
            final JsonObject spec = e.getValue()
                .getAsJsonObject();
            if (!spec.has("mod") || spec.getAsJsonObject("mod")
                .has("store")) continue;
            if (!source.contains("ViewSettings." + upper(e.getKey()) + "_KEY")) missing.add(e.getKey());
        }
        assertEquals(List.of(), missing, "settings PlannerSettings does not read by the schema's key");
    }

    @Test
    void theForgeConfigReadsItsKeysFromTheSchema() throws IOException {
        final String source = Files.readString(SOURCE.resolve("Config.java"), StandardCharsets.UTF_8);
        int seen = 0;
        for (final Map.Entry<String, JsonElement> e : json("settings.json").getAsJsonObject("settings")
            .entrySet()) {
            final JsonObject mod = e.getValue()
                .getAsJsonObject()
                .getAsJsonObject("mod");
            if (mod == null || !mod.has("store")) continue;
            seen++;
            assertTrue(source.contains("ViewSettings." + upper(e.getKey()) + "_KEY"), e.getKey());
        }
        assertTrue(seen > 0);
        assertEquals(ViewSettings.SOLVER_EFFORT_PERCENT_MIN, Config.SOLVER_EFFORT_MIN);
        assertEquals(ViewSettings.SOLVER_EFFORT_PERCENT_MAX, Config.SOLVER_EFFORT_MAX);
    }

    @Test
    void aFreshFileGivesTheSchemasDefaults(@TempDir final File dir) {
        PlannerSettings.useFile(new File(dir, "settings.properties"));
        assertEquals(ViewSettings.ZOOMED_OUT_NAMES_DEFAULT, PlannerSettings.zoomedOutNames());
        assertEquals(ViewSettings.SCROLL_CHANGES_VALUES_DEFAULT, PlannerSettings.scrollChangesValues());
        assertEquals(ViewSettings.MINIMAP_SHOW_DEFAULT, PlannerSettings.minimap());
        assertEquals(ViewSettings.MINIMAP_CIRCLE_DEFAULT, PlannerSettings.minimapCircle());
        assertEquals(ViewSettings.MINIMAP_FOLLOW_DEFAULT, PlannerSettings.minimapFollows());
        assertEquals(ViewSettings.MINIMAP_ZOOM_DEFAULT, PlannerSettings.minimapZoomIndex());
        assertEquals(ViewSettings.PIN_LIST_DEFAULT, PlannerSettings.pinList());
        assertEquals(ViewSettings.SOUND_LEVEL_DEFAULT, PlannerSettings.soundIndex());
        assertEquals(
            ViewSettings.MINIMAP_CORNER_IDS[ViewSettings.MINIMAP_CORNER_DEFAULT],
            PlannerSettings.minimapCorner()
                .name()
                .toLowerCase()
                .replace('_', '-'));
        assertEquals(
            ViewSettings.PIN_LIST_CORNER_IDS[ViewSettings.PIN_LIST_CORNER_DEFAULT],
            PlannerSettings.pinListCorner()
                .name()
                .toLowerCase()
                .replace('_', '-'));
    }

    /** A value that is not a corner is the default corner, as on the website. */
    @Test
    void aCornerThatIsNotOneIsTheDefault(@TempDir final File dir) throws IOException {
        final File file = new File(dir, "settings.properties");
        Files.writeString(
            file.toPath(),
            ViewSettings.MINIMAP_CORNER_KEY + "=SOMEWHERE\n" + ViewSettings.PIN_LIST_CORNER_KEY + "=\n",
            StandardCharsets.UTF_8);
        PlannerSettings.useFile(file);
        assertEquals(
            ViewSettings.MINIMAP_CORNER_IDS[ViewSettings.MINIMAP_CORNER_DEFAULT].toUpperCase()
                .replace('-', '_'),
            PlannerSettings.minimapCorner()
                .name());
        assertEquals(
            ViewSettings.PIN_LIST_CORNER_IDS[ViewSettings.PIN_LIST_CORNER_DEFAULT].toUpperCase()
                .replace('-', '_'),
            PlannerSettings.pinListCorner()
                .name());
    }

    @Test
    void theMinimapAndSoundChoicesAreTheSchemas() {
        assertEquals(ViewSettings.MINIMAP_SIZE_VALUES.length, PlannerSettings.MINIMAP_SIZES.length);
        assertArrayEquals(ViewSettings.MINIMAP_SIZE_LABELS, PlannerSettings.MINIMAP_SIZE_NAMES);
        assertEquals(ViewSettings.SOUND_LEVEL_VALUES.length, PlannerSettings.SOUND_GAINS.length);
        assertEquals(ViewSettings.SOUND_LEVEL_LABELS.length, PlannerSettings.SOUND_NAMES.length);
    }

    /** The Sfx enum is the sound table's mod sounds, in the table's order: a sound added to one is added to both. */
    @Test
    void theSfxEnumIsTheSoundTables() throws IOException {
        final String source = Files.readString(SOURCE.resolve("ui/sound/Sfx.java"), StandardCharsets.UTF_8);
        final Matcher m = Pattern.compile("^\\s+[A-Z_]+\\(\"([a-z_.]+)\"\\)[,;]", Pattern.MULTILINE)
            .matcher(source);
        final List<String> enumIds = new ArrayList<>();
        while (m.find()) enumIds.add(m.group(1));
        assertEquals(List.of(ViewSounds.IDS), enumIds);
    }
}
