package com.gtnhplanner.web.embed;

import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/**
 * Build-time only (not in a package named build: jvm/.gitignore ignores every build/ directory, so such a package never
 * reaches a checkout and the ServiceLoader then reports its provider as not found). Names the classpath resources TeaVM
 * embeds, for the modules whose data ships inside their jar.
 */
public final class CoreResources implements ResourceSupplier {
    @Override
    public String[] supplyResources(final ResourceSupplierContext context) {
        return new String[] {
            "assets/gtnhplanner/power/power-planner-data.json",
            "assets/gtnhplanner/power/resource-map.json",
            "assets/gtnhplanner/power/machine-icons.json",
        };
    }
}
