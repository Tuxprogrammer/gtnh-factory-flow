package com.gtnhplanner.power;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.power.PowerCatalog;

/**
 * Every placeable power source, in the website's order (registry.ts). Source ids are stored in plans; never rename a
 * shipped id.
 */
public final class PowerRegistry {

    private static List<PowerSource> sources;
    private static Map<String, PowerSource> byId;

    public static synchronized List<PowerSource> sources() {
        if (sources == null) {
            final List<PowerSource> all = PowerCatalog.sources();
            final Map<String, PowerSource> index = new LinkedHashMap<>();
            for (final PowerSource source : all) index.put(source.id(), source);
            sources = all;
            byId = index;
        }
        return sources;
    }

    @Nullable
    public static PowerSource get(final String sourceId) {
        // The planning cards are listed apart from the generator catalog (others()).
        if (CustomRate.ID.equals(sourceId)) return CustomRate.SOURCE;
        if (FluidTank.ID.equals(sourceId)) return FluidTank.SOURCE;
        if (CropFarm.ID.equals(sourceId)) return CropFarm.SOURCE;
        sources();
        return byId.get(sourceId);
    }

    /**
     * The planning cards, first in the picker's Other column: never in {@link #sources()}, the generator catalog. The
     * column's sources (the Railcraft Water Tank) follow them ({@link #inGroup}).
     */
    public static List<PowerSource> others() {
        return List.of(CustomRate.SOURCE, FluidTank.SOURCE, CropFarm.SOURCE);
    }

    public static List<PowerSource> inGroup(final PowerGroup group) {
        final List<PowerSource> out = new ArrayList<>();
        if (group == PowerGroup.OTHER) out.addAll(others());
        for (final PowerSource source : sources()) {
            if (source.group() == group) out.add(source);
        }
        return out;
    }

    private PowerRegistry() {}
}
