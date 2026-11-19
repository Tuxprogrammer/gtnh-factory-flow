package org.teavm.classlib.java.lang.management;

/** Stub: no platform beans; ojAlgo catches this and falls back to Runtime.maxMemory. */
public final class TManagementFactory {
    private TManagementFactory() {}

    public static java.lang.management.OperatingSystemMXBean getOperatingSystemMXBean() {
        throw new UnsupportedOperationException("no platform beans");
    }
}
