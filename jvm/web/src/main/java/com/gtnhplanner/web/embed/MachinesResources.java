package com.gtnhplanner.web.embed;

import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/** Build-time only: the classpath resources of core-machines that TeaVM embeds in the module (its handler and start-up tables). */
public final class MachinesResources implements ResourceSupplier {
    @Override
    public String[] supplyResources(final ResourceSupplierContext context) {
        return new String[] {
            "assets/gtnhplanner/machines/handlers.json",
            "assets/gtnhplanner/machines/fusion-startups.json",
        };
    }
}
