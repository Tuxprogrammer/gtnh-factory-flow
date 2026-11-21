package com.gtnhplanner.core.model;

/**
 * A resource as a plan names it, for a port whose host has no value of its own for it: an aspect, an item or fluid the
 * game lacks, or any resource on a card the host only shows ({@link Shown}). It is its key, the plan's name for it and
 * an amount, and nothing more; it wires by its key as every port does ({@link Wiring}).
 */
public final class SiteResource {

    /** The port type of these values. */
    public static final PortType<SiteResource> TYPE = new PortType<>() {

        @Override
        public String getKey() {
            return "site";
        }

        @Override
        public ResourceKey resource(final SiteResource value) {
            return value.key;
        }

        @Override
        public String key(final SiteResource value) {
            return value.key.toString();
        }

        @Override
        public int extractAmount(final SiteResource value) {
            return value.amount;
        }

        @Override
        public void setAmount(final SiteResource value, final int amount) {
            value.amount = amount;
        }

        @Override
        public SiteResource copy(final SiteResource value) {
            return new SiteResource(value.key, value.name, value.amount);
        }

        @Override
        public String formatDisplayName(final SiteResource value) {
            return value.name;
        }

        @Override
        public String formatAmount(final float perSecond) {
            return com.gtnhplanner.core.base.Format.compact(perSecond);
        }

        @Override
        public int color(final SiteResource value) {
            return ResourceRegistry.get()
                .color(value.key);
        }

        @Override
        public int getPinInputColor() {
            return 0xFF8A8A8A;
        }

        @Override
        public int getPinOutputColor() {
            return 0xFFB0B0B0;
        }

        @Override
        public int getArrowColor() {
            return 0xFF9A9A9A;
        }

        @Override
        public boolean same(final SiteResource a, final SiteResource b) {
            return a.key.equals(b.key);
        }

        @Override
        public boolean folds(final SiteResource a, final SiteResource b) {
            return false;
        }
    };

    private final ResourceKey key;
    private final String name;
    private int amount;

    public SiteResource(final ResourceKey key, final String name, final int amount) {
        this.key = key;
        this.name = name;
        this.amount = amount;
    }

    public ResourceKey key() {
        return key;
    }

    public String name() {
        return name;
    }
}
