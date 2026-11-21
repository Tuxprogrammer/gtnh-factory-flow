package com.gtnhplanner.data.flowchart;

/**
 * A drawer's kind and rule, as the solver reads them ({@code SolveInput.DrawerIn}) and the board's drawer keeps them
 * (the mod's {@code Drawer} implements this, so they are still {@code Drawer.Kind} and {@code Drawer.Rule} there).
 * Saved by name.
 */
public interface DrawerTypes {

    /** What the drawer is for; decides which side of a node its links are on. */
    enum Kind {

        /** Supplies node inputs. Links point at input ports. */
        SOURCE,
        /** What the player wants. Links take output ports. */
        PRODUCT,
        /**
         * Older saves' and plans' "byproduct": a product with no rule, which is what it was to the solver. Read only;
         * a drawer set to it becomes a product with no rule ({@code Drawer.setKind}, {@code Drawer.sanitize}).
         */
        BYPRODUCT,
        /** Voids what arrives. Links take output ports. */
        TRASH,
        /**
         * A drawer inside a resource network: its links are the output ports that feed it, its takers the input ports
         * it feeds. Nothing it carries leaves the plan; its rule holds what passes through.
         */
        BUFFER;

        /** True when this kind's links are node INPUT ports (only {@link #SOURCE}). */
        public boolean linksInputs() {
            return this == SOURCE;
        }

        /** True when the solver honours this kind's rule (sources, products and buffers). */
        public boolean hasRule() {
            return this == SOURCE || this == PRODUCT || this == BUFFER;
        }

        /**
         * The board's cycle key: product, trash, then product again. A source has no cycle (its
         * links are inputs) and stays a source.
         */
        public Kind next() {
            return switch (this) {
                case SOURCE -> SOURCE;
                case BUFFER -> BUFFER;
                case PRODUCT, BYPRODUCT -> TRASH;
                case TRASH -> PRODUCT;
            };
        }
    }

    /** What the drawer's total flow must satisfy against its rate. */
    enum Rule {
        /** No constraint; the drawer reads what the plan does. */
        ANY,
        /** total >= rate. */
        AT_LEAST,
        /** total == rate. */
        EXACTLY,
        /** total <= rate. */
        AT_MOST
    }
}
