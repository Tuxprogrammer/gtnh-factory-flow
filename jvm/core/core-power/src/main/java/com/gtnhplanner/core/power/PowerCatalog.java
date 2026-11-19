package com.gtnhplanner.core.power;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.sources.Endgame;
import com.gtnhplanner.power.sources.Engines;
import com.gtnhplanner.power.sources.Railcraft;
import com.gtnhplanner.power.sources.Reactors;
import com.gtnhplanner.power.sources.Singleblocks;
import com.gtnhplanner.power.sources.Sofc;
import com.gtnhplanner.power.sources.SteamMakers;
import com.gtnhplanner.power.sources.Turbines;

/**
 * Every generator source, in the website's order (registry.ts POWER_SOURCES). Source ids are stored in plans; never
 * rename a shipped id. The planning cards (custom rate, fluid tank, crop farm) are not sources here: they hold a plan
 * node and live with the planning cards.
 */
public final class PowerCatalog {

    private static List<PowerSource> sources;
    private static Map<String, PowerSource> byId;

    public static synchronized List<PowerSource> sources() {
        if (sources == null) {
            final List<PowerSource> all = new ArrayList<>();
            all.addAll(Singleblocks.sources());
            all.addAll(Engines.sources());
            all.addAll(Sofc.sources());
            all.addAll(SteamMakers.sources());
            all.addAll(Turbines.sources());
            all.addAll(Reactors.reactorSources());
            all.addAll(Reactors.passiveSources());
            all.addAll(Endgame.sources());
            // The Other column's one generator source, the Railcraft Water Tank.
            all.addAll(Railcraft.otherSources());
            final Map<String, PowerSource> index = new LinkedHashMap<>();
            for (final PowerSource source : all) index.put(source.id(), source);
            sources = Collections.unmodifiableList(all);
            byId = index;
        }
        return sources;
    }

    /** The source with this id, or null (getPowerSource). */
    @Nullable
    public static PowerSource get(final String sourceId) {
        sources();
        return byId.get(sourceId);
    }

    private PowerCatalog() {}
}
